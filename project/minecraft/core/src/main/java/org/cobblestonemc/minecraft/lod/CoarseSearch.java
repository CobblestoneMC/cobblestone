/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.jetbrains.annotations.Nullable;

/**
 * A Dijkstra over section profiles, run <b>backward from the destination</b>, whose settled costs
 * are the heuristic the fine search reads.
 *
 * <p><b>Backward is about which quantity the costs are, not about laziness.</b> The fine search
 * asks "what remains from here?" for hundreds of thousands of different cells. Settled from the
 * destination, a section's cost <em>is</em> that answer for every cell inside it, and one search
 * serves every query. Forward from the origin it would be cost-so-far, which says nothing about
 * what is left, and answering even one query would need its own search to the goal.
 *
 * <p>⚠️ <b>Edges are priced in the forward direction.</b> Expanding from section B to its neighbour
 * A means the agent will travel A → B, so the cost charged is the cost of crossing A that way.
 * Reversing the traversal without reversing the pricing would cost a descent as an ascent, and
 * misjudge every vertical route.
 *
 * <p><b>One node per section, 26-connected.</b> Not one node per connected component: an estimate
 * that is too low only costs expanded nodes, while one that is too high distorts which route A*
 * prefers, and splitting sections at walls raises it — see {@link SectionProfile#whole()}. And all
 * 26 neighbours rather than the 6 faces: an axis-aligned coarse route is a Manhattan path, which
 * overstates a straight line by up to 1.41× across a plane and 1.73× through a corner, and that
 * error lands directly in every estimate.
 *
 * <p>Lazily expanded and resumable: a query runs the search only as far as it must to answer, keeps
 * everything it settles, and can be asked again. So the work done is exactly what the fine search
 * turns out to need.
 */
public final class CoarseSearch {

  /** Edge length of a section, in blocks. */
  public static final int SECTION = 16;

  /** The 26 neighbouring sections, as {@code {dx, dy, dz}}. */
  private static final int[][] NEIGHBOURS = neighbours();

  private final SectionProfiles profiles;
  private final CoarseCost cost;
  private final Cell goal;

  private final Map<Long, Double> settled = new HashMap<>();
  private final Map<Long, Double> best = new HashMap<>();
  private final PriorityQueue<Entry> open =
      new PriorityQueue<>(java.util.Comparator.comparingDouble(Entry::cost));

  private long expansions;
  private boolean exhausted;
  private @Nullable CompletableFuture<Void> pending;

  /**
   * Creates a search seeded at the destination.
   *
   * @param profiles where section profiles come from
   * @param cost the agent's cost model
   * @param goal the destination cell
   */
  public CoarseSearch(SectionProfiles profiles, CoarseCost cost, Cell goal) {
    this.profiles = profiles;
    this.cost = cost;
    this.goal = goal;
    // Seeded unconditionally. Whether the goal's own section can be profiled is not knowable
    // without possibly fetching a chunk, and the search is the thing that knows how to wait.
    long key = key(section(goal.x()), section(goal.y()), section(goal.z()));
    best.put(key, 0.0);
    open.add(new Entry(key, 0.0));
  }

  /**
   * Returns the estimated seconds from {@code cell} to the goal, expanding the search only as far
   * as needed.
   *
   * <p>Two terms. The coarse value settles a whole section at once, which on its own makes the
   * estimate a <b>staircase</b>: every cell of a section gets the same answer while the true
   * remaining cost falls smoothly across it. Over a route only a few sections long that is most of
   * the estimate's error, and it is systematically high, since the constant within each tread is
   * the value at the section's far edge.
   *
   * <p>So a within-section term is added: how much nearer the goal this cell is than its section's
   * centre, priced at the cheapest rate the agent has. Cheapest, because this correction refines
   * the coarse value and must never overwhelm it.
   *
   * @param cell the cell being estimated
   * @param budget how many section expansions this query may spend
   * @return the estimate, never negative and never infinite
   */
  public double costToGoal(Cell cell, long budget) {
    return Math.max(0.0, sectionCostToGoal(cell, budget) + withinSection(cell));
  }

  /** How much nearer the goal this cell is than the centre of its own section, priced cheaply. */
  private double withinSection(Cell cell) {
    Cell centre =
        new Cell(
            section(cell.x()) * SECTION + SECTION / 2,
            section(cell.y()) * SECTION + SECTION / 2,
            section(cell.z()) * SECTION + SECTION / 2);
    return (cell.distance(goal) - centre.distance(goal)) * cost.cheapestCostPerBlock();
  }

  /**
   * Returns the settled coarse cost for the section containing {@code cell}, without the
   * within-section refinement.
   *
   * @param cell the cell
   * @param budget how many section expansions this query may spend
   * @return the section's cost to the goal
   */
  public double sectionCostToGoal(Cell cell, long budget) {
    long want = key(section(cell.x()), section(cell.y()), section(cell.z()));
    Double known = settled.get(want);
    if (known != null) {
      return known;
    }

    long spent = 0;
    while (spent < budget && !open.isEmpty()) {
      Entry entry = open.peek();
      Double bestKnown = best.get(entry.key());
      if (bestKnown == null || entry.cost() > bestKnown) {
        open.poll();
        continue; // superseded
      }
      if (settled.containsKey(entry.key())) {
        open.poll();
        continue;
      }
      // Resident first, then commit. An expansion that discovered halfway through that it needed
      // a chunk would have to undo a settle, and a half-settled Dijkstra is not a Dijkstra.
      CompletableFuture<Void> waiting = fetch(entry);
      if (waiting != null) {
        pending = waiting;
        return backstop(cell);
      }
      open.poll();
      settled.put(entry.key(), entry.cost());
      expansions++;
      spent++;
      expand(entry);
      if (entry.key() == want) {
        return entry.cost();
      }
    }
    if (open.isEmpty()) {
      exhausted = true;
    }
    // Not settled within budget, or genuinely unreachable in the coarse graph. Answer
    // optimistically — never infinity. A profile is an approximation, and turning one into a fence
    // would let an imperfect summary make a real route unreachable.
    return backstop(cell);
  }

  private double backstop(Cell cell) {
    return cell.distance(goal) * cost.cheapestCostPerBlock();
  }

  /**
   * Makes sure every profile an expansion will read is in hand.
   *
   * @param entry the section about to be expanded
   * @return a future completing when the missing profiles arrive, or {@code null} if none are
   */
  private @Nullable CompletableFuture<Void> fetch(Entry entry) {
    int sx = unpackX(entry.key());
    int sy = unpackY(entry.key());
    int sz = unpackZ(entry.key());
    List<CompletableFuture<SectionProfile>> waiting = null;
    for (int[] offset : NEIGHBOURS) {
      FutureOr<SectionProfile> neighbour =
          profiles.at(sx + offset[0], sy + offset[1], sz + offset[2]);
      if (!neighbour.isImmediate()) {
        if (waiting == null) {
          waiting = new ArrayList<>();
        }
        waiting.add(neighbour.future());
      }
    }
    // All of them, not the first: the 26 fetches go out together and are waited on once, rather
    // than parking the whole fine search 26 times over for one section.
    return waiting == null
        ? null
        : CompletableFuture.allOf(waiting.toArray(new CompletableFuture[0]));
  }

  /**
   * Returns the future the last query stopped on, clearing it.
   *
   * @return the future, or {@code null} if the last query did not stop
   */
  public @Nullable CompletableFuture<Void> takePending() {
    CompletableFuture<Void> waiting = pending;
    pending = null;
    return waiting;
  }

  private void expand(Entry entry) {
    int sx = unpackX(entry.key());
    int sy = unpackY(entry.key());
    int sz = unpackZ(entry.key());

    for (int[] offset : NEIGHBOURS) {
      int nx = sx + offset[0];
      int ny = sy + offset[1];
      int nz = sz + offset[2];
      // Immediate by construction: fetch() ran first and returned only once all 26 were resident.
      SectionProfile neighbour = profiles.at(nx, ny, nz).value();
      if (neighbour == null) {
        continue;
      }
      SectionProfile.Component into = neighbour.whole();
      if (into.openVolume() == 0 && into.coverage(SectionProfile.Axis.X, Medium.MINE) == 0) {
        continue; // solid and unbreakable: bedrock, or the bottom of the world
      }
      // Priced forward: the agent will travel neighbour -> here, so it crosses `into` on the way.
      // Charging the section being expanded from would price the wrong terrain.
      double span =
          SECTION
              * Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2]);
      relax(key(nx, ny, nz), entry.cost() + cost.crossingCost(into, span, axesOf(offset)));
    }
  }

  private void relax(long key, double newCost) {
    Double existing = best.get(key);
    if (existing == null || newCost < existing) {
      best.put(key, newCost);
      open.add(new Entry(key, newCost));
    }
  }

  private static int[][] neighbours() {
    int[][] offsets = new int[26][];
    int at = 0;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx != 0 || dy != 0 || dz != 0) {
            offsets[at++] = new int[] {dx, dy, dz};
          }
        }
      }
    }
    return offsets;
  }

  private static SectionProfile.Axis[] axesOf(int[] offset) {
    int count = (offset[0] != 0 ? 1 : 0) + (offset[1] != 0 ? 1 : 0) + (offset[2] != 0 ? 1 : 0);
    SectionProfile.Axis[] axes = new SectionProfile.Axis[count];
    int at = 0;
    if (offset[0] != 0) {
      axes[at++] = SectionProfile.Axis.X;
    }
    if (offset[1] != 0) {
      axes[at++] = SectionProfile.Axis.Y;
    }
    if (offset[2] != 0) {
      axes[at] = SectionProfile.Axis.Z;
    }
    return axes;
  }

  /**
   * Returns how many sections have been settled.
   *
   * @return the settled count
   */
  public int settledCount() {
    return settled.size();
  }

  /**
   * Returns how many section expansions this search has done in total.
   *
   * @return the expansion count
   */
  public long expansions() {
    return expansions;
  }

  /**
   * Returns whether the search ran out of reachable sections.
   *
   * @return {@code true} if the frontier emptied
   */
  public boolean exhausted() {
    return exhausted;
  }

  private static int section(int worldCoordinate) {
    return Math.floorDiv(worldCoordinate, SECTION);
  }

  /**
   * Packs a section's address into a {@code long}.
   *
   * <p>Section Y gets eight bits, not five. A modern overworld runs from section -4 to 19, and the
   * End and custom worlds can be taller still; five bits would have silently wrapped section 19
   * onto section -13 and mixed two parts of the world into one map entry.
   *
   * @param sx the section X
   * @param sy the section Y
   * @param sz the section Z
   * @return the packed key
   */
  public static long key(int sx, int sy, int sz) {
    return ((long) (sx & 0x3F_FFFF) << 30) | ((long) (sz & 0x3F_FFFF) << 8) | (sy & 0xFF);
  }

  /**
   * Unpacks a key's section X.
   *
   * @param key the packed key
   * @return the section X
   */
  public static int unpackX(long key) {
    // Cast to int first: the shifts that sign-extend a 22-bit field have to happen in int
    // arithmetic, or they sign-extend from bit 63 and every negative section X comes back positive.
    return (int) (key >>> 30) << 10 >> 10;
  }

  /**
   * Unpacks a key's section Z.
   *
   * @param key the packed key
   * @return the section Z
   */
  public static int unpackZ(long key) {
    return (int) ((key >> 8) & 0x3F_FFFF) << 10 >> 10;
  }

  /**
   * Unpacks a key's section Y.
   *
   * @param key the packed key
   * @return the section Y
   */
  public static int unpackY(long key) {
    return (byte) (key & 0xFF);
  }

  private record Entry(long key, double cost) {}

  /**
   * Where the search gets profiles.
   *
   * <p>An interface so the search does not care whether a profile is resident, read from disk, or
   * computed on the spot from chunk data just fetched.
   */
  public interface SectionProfiles {

    /**
     * Returns the profile for a section, computing it if needed, or an immediate {@code null} if
     * that section cannot be known.
     *
     * <p>May be {@link FutureOr.Pending} where the chunks have to be fetched. The search then stops
     * rather than guessing, and resumes when they land.
     *
     * <p>⚠️ <b>An implementation must remember what it fetched.</b> Once a returned future has
     * completed, a later call for that same section has to answer immediately; a source that
     * re-requests would leave the search parking on the same section forever, making no progress
     * and never failing.
     *
     * @param sectionX the section X
     * @param sectionY the section Y
     * @param sectionZ the section Z
     * @return the profile, or {@code null}
     */
    FutureOr<SectionProfile> at(int sectionX, int sectionY, int sectionZ);
  }
}
