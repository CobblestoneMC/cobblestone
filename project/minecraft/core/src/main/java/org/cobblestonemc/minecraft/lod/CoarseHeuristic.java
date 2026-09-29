/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.HeuristicStrategy;
import org.cobblestonemc.SolveHeuristic;
import org.cobblestonemc.api.TraversalState;
import org.jetbrains.annotations.Nullable;

/**
 * The fine search's heuristic, answered from the coarse layer.
 *
 * <p>This is the point of everything under {@code lod}: instead of pricing the remaining journey at
 * a per-block rate extrapolated from the last few steps — which is what makes a search drink a
 * river, because the river really is cheap and simply does not go anywhere — the estimate comes
 * from a search that has already looked at where the terrain goes.
 *
 * <p><b>Costs are frozen once handed out.</b> The coarse search is lazily expanded, so a section
 * asked about early may settle at a higher cost later. Letting an estimate rise mid-solve would
 * break the ordering A* assumes, and the repair machinery that could absorb it is the most delicate
 * code in the search. Remembering the first answer is stale and simple; the alternative is accurate
 * and entangled, and the harness can say later whether the accuracy was worth it.
 *
 * <p>⚠️ <b>Profiles are read synchronously.</b> A live server reaches chunks through a future, and
 * this interface has nowhere to park — so a production wiring must run the coarse pass before the
 * fine search rather than during it, or fall back for sections whose chunks are not already in
 * hand. The benchmark platform holds its capture in memory, so it can answer immediately, which is
 * what makes measuring the idea possible before that plumbing exists.
 */
public final class CoarseHeuristic implements HeuristicStrategy {

  /**
   * Section expansions one estimate may spend before falling back.
   *
   * <p><b>Unbounded, and measured that way.</b> A budget looks prudent -- it caps the work inside a
   * single heuristic call -- but what it actually does is hand back the optimistic euclidean
   * backstop, which {@link Solve} then freezes for that whole section. The fine search spends the
   * rest of the solve steering by a number the coarse layer could have answered exactly.
   *
   * <p>At 4 096 it bound on 7 of 22 scenarios, and lifting it was worth up to 5x on expanded nodes
   * at equal or better path cost: {@code overworld/ocean} as a miner went 9 788 -> 1 964 nodes at
   * w=2.5 while the path got cheaper (208.86 -> 206.93), and 14 623 -> 6 811 at w=1.5 for an
   * identical path. Nothing measured got meaningfully worse.
   *
   * <p>⚠️ This is only affordable because {@link CoarseSearch} is bounded by the terrain it can
   * see, and because the profiles behind it answer synchronously. A production wiring reaching
   * chunks through futures cannot expand without parking, which is the real fix -- the budget was
   * standing in for that plumbing and paying for it in estimate quality.
   */
  private static final long QUERY_BUDGET = Long.MAX_VALUE;

  private final CoarseSearch.SectionProfiles profiles;
  private final CoarseCost cost;
  private final double cheapestCostPerBlock;

  /**
   * Creates a heuristic over a profile source.
   *
   * @param profiles where section profiles come from
   * @param cost the agent's cost model
   */
  public CoarseHeuristic(CoarseSearch.SectionProfiles profiles, CoarseCost cost) {
    this.profiles = profiles;
    this.cost = cost;
    this.cheapestCostPerBlock = cost.cheapestCostPerBlock();
  }

  @Override
  public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
    // Tier 1 asks this without a solve behind it, and wants a cheap admissible bound rather than a
    // coarse search per query.
    return from.distance(target.nearestBoundaryCell(from)) * cheapestCostPerBlock;
  }

  @Override
  public SolveHeuristic newSolve(int windowWidth, DomainRegion<?> target) {
    return new Solve(target);
  }

  /** One solve's view: a backward coarse search, seeded on first use, and its answers. */
  private final class Solve implements SolveHeuristic {

    private final DomainRegion<?> target;
    private final Map<Long, Double> frozen = new HashMap<>();
    private CoarseSearch search;

    Solve(DomainRegion<?> target) {
      this.target = target;
    }

    @Override
    public double seed() {
      return cheapestCostPerBlock;
    }

    @Override
    public double advance(double trailAverage, double stepCost, double blocks) {
      // Nothing to learn along the way: the estimate comes from the terrain ahead rather than from
      // the terrain behind, which is the whole difference from the running-average heuristic.
      return trailAverage;
    }

    @Override
    public @Nullable CompletableFuture<Void> prepare(Cell from) {
      long section = sectionOf(from);
      if (frozen.containsKey(section)) {
        return null; // already answered; the common case, and it allocates nothing
      }
      if (search == null) {
        // Seeded here rather than in the constructor because the goal is a region, and the cell of
        // it worth aiming at depends on where the search actually starts.
        search = new CoarseSearch(profiles, cost, target.nearestBoundaryCell(from));
      }
      double estimate = search.sectionCostToGoal(from, QUERY_BUDGET);
      CompletableFuture<Void> waiting = search.takePending();
      if (waiting != null) {
        // The coarse search stopped on a chunk. Nothing is frozen: what it would have returned is
        // the optimistic backstop, and remembering that is exactly the mistake the budget used to
        // make. The fine search parks and asks again once the terrain is here.
        return waiting;
      }
      frozen.put(section, estimate);
      return null;
    }

    @Override
    public double estimate(Cell from, double distance, TraversalState state, double trailAverage) {
      long section = sectionOf(from);
      Double known = frozen.get(section);
      if (known != null) {
        // Within a section the coarse term is constant, so the within-section refinement still has
        // to be applied per cell — freezing is about the section's cost, not about the cell's.
        return known + withinSection(from);
      }
      // Reached only when something estimates without preparing. Answer from whatever the coarse
      // search can say now, and do not freeze it: a caller that skipped the park has not earned a
      // permanent answer.
      if (search == null) {
        search = new CoarseSearch(profiles, cost, target.nearestBoundaryCell(from));
      }
      double estimate = search.sectionCostToGoal(from, QUERY_BUDGET);
      if (search.takePending() == null) {
        frozen.put(section, estimate);
      }
      return estimate + withinSection(from);
    }

    private long sectionOf(Cell from) {
      return CoarseSearch.key(
          Math.floorDiv(from.x(), CoarseSearch.SECTION),
          Math.floorDiv(from.y(), CoarseSearch.SECTION),
          Math.floorDiv(from.z(), CoarseSearch.SECTION));
    }

    /** How much nearer the goal this cell is than its section's centre, priced cheaply. */
    private double withinSection(Cell from) {
      Cell goal = target.nearestBoundaryCell(from);
      int size = CoarseSearch.SECTION;
      Cell centre =
          new Cell(
              Math.floorDiv(from.x(), size) * size + size / 2,
              Math.floorDiv(from.y(), size) * size + size / 2,
              Math.floorDiv(from.z(), size) * size + size / 2);
      return Math.max(
          -cheapestCostPerBlock * size,
          (from.distance(goal) - centre.distance(goal)) * cheapestCostPerBlock);
    }
  }
}
