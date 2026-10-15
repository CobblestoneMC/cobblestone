/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.cobblestonemc.Cell;
import org.jetbrains.annotations.Nullable;

/**
 * A backward search over a {@link RegionGraph}'s portals, resumed whenever the fine search asks
 * about a cell it cannot yet answer for.
 *
 * <p><b>Reverse Resumable A*.</b> The search runs from the destination toward the fine search's
 * origin, A* style, with straight-line distance to the origin at the agent's cheapest rate as its
 * heuristic. A portal it settles knows its true cost to the destination over the graph, and that
 * stays true however much further the search runs, so every answer is final the moment it is given.
 * Focusing on the origin is what keeps it from flooding the whole world around the destination —
 * the fine search starts at the origin and works outward, so that is where the questions come from.
 *
 * <p><b>Portals carry a point, not just a cost.</b> A portal is a box (a whole section face, for a
 * plain), and pricing a crossing to its nearest corner would let a diagonal route slip through
 * every section corner for free. So each portal's label holds the point the cheapest route crosses
 * it at, and the next crossing is priced from that point to the nearest point of the next box. It
 * is a greedy approximation of the continuous shortest path, and it is what keeps a straight route
 * straight through a chain of section-sized regions.
 *
 * <p><b>An answer for a cell</b> in region {@code R} is the least, over {@code R}'s settled portals
 * {@code q}, of crossing {@code R} to {@code q} plus {@code q}'s cost. The search only has to run
 * until nothing still on its frontier could beat that, which with an origin-focused heuristic is
 * when {@code fMin - cheapest · |cell - origin| >= answer}. Usually that is already true and the
 * query costs a scan of {@code R}'s portals and nothing else.
 */
public final class RegionSearch {

  private static final byte UNSEEN = 0;
  private static final byte OPEN = 1;
  private static final byte SETTLED = 2;

  private final RegionGraph graph;
  private final RegionCost cost;
  private final Cell goal;
  private final Cell origin;
  private final double cheapest;

  private double[] g = new double[0];
  private double[] px = new double[0];
  private double[] py = new double[0];
  private double[] pz = new double[0];
  private byte[] state = new byte[0];

  private final Heap open = new Heap();

  /** The goal's region, once found: {@link RegionGraph#NONE} if it has none the agent can use. */
  private int goalRegion = Integer.MIN_VALUE;

  /**
   * How many regions the flood through a destination may look at. A destination is usually a point
   * or a building; a whole town is thousands of regions, and its nearest parts are what an estimate
   * needs.
   */
  static final int MAX_DESTINATION_REGIONS = 4096;

  /** Whether a cell is part of the destination. */
  private final Predicate<Cell> inside;

  /** Every region seeded as arrived, with the point inside the destination it is priced to. */
  private final Map<Integer, double[]> arrivals = new HashMap<>();

  private final ArrayDeque<Integer> flood = new ArrayDeque<>();
  private int goalSeed = RegionGraph.NONE;
  private final Set<Integer> flooded = new HashSet<>();

  private long expansions;
  private long queries;
  private long queryNanos;
  private long terrainNanos;
  private @Nullable CompletableFuture<Void> pending;

  /**
   * Creates a search seeded at the destination and focused on the origin.
   *
   * @param graph the terrain
   * @param cost the agent
   * @param goal the destination
   * @param origin where the fine search starts
   */
  public RegionSearch(RegionGraph graph, RegionCost cost, Cell goal, Cell origin) {
    this(graph, cost, goal, origin, goal::equals);
  }

  /**
   * Creates a search seeded at a destination that may be an area, and focused on the origin.
   *
   * <p>The search starts from every region inside the destination that connects to {@code goal},
   * the destination's nearest cell to the origin: a town a river enters from the far side is
   * reached by the river, and an estimate measured to the nearest edge alone would steer the fine
   * search away from it.
   *
   * @param graph the terrain
   * @param cost the agent
   * @param goal the destination's cell nearest the origin
   * @param origin where the fine search starts
   * @param inside whether a cell is part of the destination
   */
  public RegionSearch(
      RegionGraph graph, RegionCost cost, Cell goal, Cell origin, Predicate<Cell> inside) {
    this.graph = graph;
    this.cost = cost;
    this.goal = goal;
    this.origin = origin;
    this.cheapest = cost.cheapest();
    this.inside = inside;
  }

  /**
   * Returns the estimated seconds from a cell to the destination, running the search only as far as
   * that answer needs.
   *
   * <p>If terrain had to be fetched, the answer is an optimistic placeholder and {@link
   * #takePending()} returns what to wait for; ask again once it completes.
   *
   * @param cell the cell
   * @return the estimate, never negative and never infinite
   */
  public double costToGoal(Cell cell) {
    long started = System.nanoTime();
    try {
      return answer(cell);
    } finally {
      queries++;
      queryNanos += System.nanoTime() - started;
    }
  }

  /**
   * Returns how many questions this search has answered, placeholders included.
   *
   * @return the query count
   */
  public long queries() {
    return queries;
  }

  /**
   * Returns the time spent answering, including any terrain the answers had to build.
   *
   * @return nanoseconds
   */
  public long queryNanos() {
    return queryNanos;
  }

  /**
   * Returns the time spent searching: answering, less whatever the answers spent waiting on the
   * graph for terrain — placing columns, joining seams, and any read or build that ran inside.
   *
   * @return nanoseconds
   */
  public long searchNanos() {
    return queryNanos - terrainNanos;
  }

  private @Nullable CompletableFuture<Void> completing(int x, int z) {
    long started = System.nanoTime();
    try {
      return graph.complete(x, z);
    } finally {
      terrainNanos += System.nanoTime() - started;
    }
  }

  private @Nullable CompletableFuture<Void> completingRegion(int region) {
    long started = System.nanoTime();
    try {
      return graph.completeRegion(region);
    } finally {
      terrainNanos += System.nanoTime() - started;
    }
  }

  private double answer(Cell cell) {
    double backstop = cell.distance(goal) * cheapest;
    CompletableFuture<Void> waiting = completing(cell.x(), cell.z());
    if (waiting != null) {
      pending = waiting;
      return backstop;
    }
    if (goalRegion == Integer.MIN_VALUE) {
      waiting = seed();
      if (waiting != null) {
        pending = waiting;
        return backstop;
      }
    }
    int region = usableRegionNear(cell.x(), cell.y(), cell.z());
    // No goal region: the destination is somewhere the graph has nothing for this agent, so it
    // can say nothing about the way there either.
    if (region == RegionGraph.NONE || goalRegion == RegionGraph.NONE) {
      // Somewhere the graph has nothing this agent can stand in: mid-air, or in a cell the builder
      // could not class. Optimistic, because a guess that is too high here would be a wall.
      return backstop;
    }
    waiting = completingRegion(region);
    if (waiting != null) {
      pending = waiting;
      return backstop;
    }

    // A ceiling, so a region the graph cannot reach does not send the search across the world to
    // prove it: past the price of tunnelling straight there, no answer is worth more search.
    double answer = cell.distance(goal) * cost.fallback();
    double[] arrival = arrivals.get(region);
    if (arrival != null) {
      answer =
          inside.test(cell)
              ? 0
              : Math.min(
                  answer,
                  cross(region, cell.x(), cell.y(), cell.z(), arrival[0], arrival[1], arrival[2]));
    }
    int portals = graph.portalCount(region);
    for (int i = 0; i < portals; i++) {
      int portal = graph.portal(region, i);
      if (portal < state.length && state[portal] == SETTLED) {
        answer = Math.min(answer, through(region, portal, cell));
      }
    }
    double slack = cheapest * cell.distance(origin);
    while (!open.isEmpty() && open.minKey() - slack < answer) {
      int settled = expand();
      if (settled == -2) {
        return backstop; // parked; pending is set
      }
      if (settled >= 0 && (graph.portalA(settled) == region || graph.portalB(settled) == region)) {
        answer = Math.min(answer, through(region, settled, cell));
      }
    }
    return answer;
  }

  /** What leaving {@code region} through a settled portal costs from a cell, plus the rest. */
  private double through(int region, int portal, Cell cell) {
    if (graph.directed(portal) && graph.portalA(portal) != region) {
      return Double.MAX_VALUE; // a fall, and this is the bottom of it
    }
    return g[portal]
        + toward(region, cell.x(), cell.y(), cell.z(), portal, px[portal], py[portal], pz[portal]);
  }

  /**
   * Returns what it costs to cross {@code region} from a point to {@code onward}'s label point
   * {@code (tx, ty, tz)}, or to the goal when {@code onward} is negative.
   *
   * <p>Two legs: across the region to the nearest point of the onward portal, then along that
   * portal to its label point. The second leg is priced at whichever of the portal's two regions is
   * cheaper, because a portal's box is a contact between them and can be long — the whole floor
   * under a patch of ground, joined to the side of a wall beside it. Pricing the walk along the
   * floor as a dig through the rock beneath it made a one-block wall cost fifteen blocks of mining.
   */
  private double toward(
      int region, double fx, double fy, double fz, int onward, double tx, double ty, double tz) {
    if (onward < 0) {
      return cross(region, fx, fy, fz, tx, ty, tz);
    }
    double ex = clamp(fx, onward, 0);
    double ey = clamp(fy, onward, 1);
    double ez = clamp(fz, onward, 2);
    double along = cross(region, ex, ey, ez, tx, ty, tz);
    int a = graph.portalA(onward);
    int b = graph.portalB(onward);
    int other = a == region ? b : a;
    if (cost.usable(graph.regionClass(other))) {
      along = Math.min(along, cross(other, ex, ey, ez, tx, ty, tz));
    }
    // The detour along the contact only when it pays: on uniform terrain it is an L where the
    // straight line is shorter, and taking it would put the staircase back.
    return Math.min(
        cross(region, fx, fy, fz, tx, ty, tz), cross(region, fx, fy, fz, ex, ey, ez) + along);
  }

  /**
   * Seeds the search with the destination: the goal's region, and every region the destination
   * holds that connects to it.
   *
   * <p>A flood outward from the goal's region, through regions of any class, keeping each usable
   * region whose middle is inside the destination; resumed where it left off when it has to wait
   * for terrain. For a destination that is one cell, only the goal's region.
   *
   * @return a future to wait on, or {@code null} once seeded
   */
  private @Nullable CompletableFuture<Void> seed() {
    if (flooded.isEmpty()) {
      CompletableFuture<Void> waiting = completing(goal.x(), goal.z());
      if (waiting != null) {
        return waiting;
      }
      int region = usableRegionNear(goal.x(), goal.y(), goal.z());
      if (region == RegionGraph.NONE) {
        goalRegion = RegionGraph.NONE;
        return null;
      }
      flooded.add(region);
      flood.add(region);
      arrivals.put(region, new double[] {goal.x(), goal.y(), goal.z()});
      goalSeed = region;
    }
    while (!flood.isEmpty() && flooded.size() < MAX_DESTINATION_REGIONS) {
      int region = flood.peek();
      CompletableFuture<Void> waiting = completingRegion(region);
      if (waiting != null) {
        return waiting;
      }
      flood.poll();
      int portals = graph.portalCount(region);
      for (int i = 0; i < portals; i++) {
        int portal = graph.portal(region, i);
        int other = graph.portalA(portal) == region ? graph.portalB(portal) : graph.portalA(portal);
        if (!flooded.add(other)) {
          continue;
        }
        double mx = (graph.regionBound(other, 0) + graph.regionBound(other, 3)) / 2.0;
        double my = graph.regionBound(other, 1);
        double mz = (graph.regionBound(other, 2) + graph.regionBound(other, 5)) / 2.0;
        if (!inside.test(new Cell((int) Math.floor(mx), (int) my, (int) Math.floor(mz)))) {
          continue; // outside: the flood stops at the destination's edge
        }
        flood.add(other);
        if (usable(other)) {
          arrivals.put(other, new double[] {mx, my, mz});
        }
      }
    }
    grow();
    for (Map.Entry<Integer, double[]> arrival : arrivals.entrySet()) {
      int region = arrival.getKey();
      double[] at = arrival.getValue();
      int portals = graph.portalCount(region);
      for (int i = 0; i < portals; i++) {
        int portal = graph.portal(region, i);
        if (enters(portal, region)) {
          reach(portal, region, 0, -1, at[0], at[1], at[2]);
        }
      }
    }
    goalRegion = goalSeed;
    return null;
  }

  /**
   * Settles the cheapest portal on the frontier and relaxes across both its regions.
   *
   * @return the portal settled, {@code -1} if the frontier entry was stale, or {@code -2} if the
   *     search had to stop for terrain
   */
  private int expand() {
    int portal = open.minValue();
    double key = open.minKey();
    if (state[portal] == SETTLED || key > fOf(portal) + 1e-9) {
      open.pop();
      return -1;
    }
    // Resident first, then commit: a settle that discovered halfway through that it needed a
    // column would leave half its neighbours relaxed.
    CompletableFuture<Void> waiting = completingRegion(graph.portalA(portal));
    if (waiting == null) {
      waiting = completingRegion(graph.portalB(portal));
    }
    if (waiting != null) {
      pending = waiting;
      return -2;
    }
    open.pop();
    state[portal] = SETTLED;
    expansions++;
    grow();
    // A fall is only crossed downward, so its cost was reached from the region landed in and the
    // search continues into the ledge it was fallen from — never the other way.
    relax(portal, graph.portalA(portal));
    if (!graph.directed(portal)) {
      relax(portal, graph.portalB(portal));
    }
    return portal;
  }

  /**
   * Prices every other portal of {@code region} as a crossing of it that ends at {@code from}.
   *
   * <p>The agent travels toward the destination, so a portal reached here is crossed <em>into</em>
   * {@code region} and then the region is crossed to {@code from}: the cost is of that direction,
   * which matters for anything vertical.
   */
  private void relax(int from, int region) {
    if (!cost.usable(graph.regionClass(region))) {
      return;
    }
    double fx = px[from];
    double fy = py[from];
    double fz = pz[from];
    double base = g[from];
    int portals = graph.portalCount(region);
    for (int i = 0; i < portals; i++) {
      int portal = graph.portal(region, i);
      if (portal == from || state[portal] == SETTLED || !enters(portal, region)) {
        continue;
      }
      reach(portal, region, base, from, fx, fy, fz);
    }
  }

  /**
   * Labels {@code portal} as crossed on the way through {@code region} to {@code onward}'s label
   * point {@code (fx, fy, fz)}, which costs {@code base} onward; {@code onward} is negative when
   * that point is the goal itself.
   *
   * <p><b>Where on the portal.</b> Its point nearest the one being reached is the obvious choice
   * and the wrong one: chained across a run of section seams it turns every diagonal into a
   * staircase, each crossing hugging the corner nearest the last, and a plain priced that way costs
   * Manhattan distance. The other candidate is where the straight line from that point toward the
   * origin passes the portal, which is where a route the fine search will actually ask about
   * crosses it. Both are tried and the one with the lower {@code f} kept: it is the label the
   * search would settle first, and either is a valid crossing, so the stopping rule holds.
   */
  private void reach(
      int portal, int region, double base, int onward, double fx, double fy, double fz) {
    double nx = clamp(fx, portal, 0);
    double ny = clamp(fy, portal, 1);
    double nz = clamp(fz, portal, 2);
    double near = base + toward(region, nx, ny, nz, onward, fx, fy, fz);

    // The point of the segment toward the origin closest to the portal's centre, pulled onto it.
    double ox = origin.x() - fx;
    double oy = origin.y() - fy;
    double oz = origin.z() - fz;
    double length = ox * ox + oy * oy + oz * oz;
    double t = 0;
    if (length > 0) {
      double cx = (graph.portalBound(portal, 0) + graph.portalBound(portal, 3)) / 2.0 - fx;
      double cy = (graph.portalBound(portal, 1) + graph.portalBound(portal, 4)) / 2.0 - fy;
      double cz = (graph.portalBound(portal, 2) + graph.portalBound(portal, 5)) / 2.0 - fz;
      t = Math.max(0, Math.min(1, (cx * ox + cy * oy + cz * oz) / length));
    }
    double ax = clamp(fx + t * ox, portal, 0);
    double ay = clamp(fy + t * oy, portal, 1);
    double az = clamp(fz + t * oz, portal, 2);
    double aimed = base + toward(region, ax, ay, az, onward, fx, fy, fz);

    if (aimed + focus(ax, ay, az) < near + focus(nx, ny, nz)) {
      label(portal, aimed, ax, ay, az);
    } else {
      label(portal, near, nx, ny, nz);
    }
  }

  /**
   * Whether an agent can arrive in {@code region} through {@code portal}: the other side must be
   * somewhere it can be, and a fall must land here rather than start here.
   */
  private boolean enters(int portal, int region) {
    int other = graph.portalA(portal) == region ? graph.portalB(portal) : graph.portalA(portal);
    if (graph.directed(portal) && graph.portalB(portal) != region) {
      return false;
    }
    return cost.usable(graph.regionClass(other));
  }

  private void label(int portal, double cost, double x, double y, double z) {
    if (cost >= g[portal]) {
      return;
    }
    g[portal] = cost;
    px[portal] = x;
    py[portal] = y;
    pz[portal] = z;
    state[portal] = OPEN;
    open.push(fOf(portal), portal);
  }

  private double fOf(int portal) {
    return g[portal] + focus(px[portal], py[portal], pz[portal]);
  }

  /** The search's heuristic: straight to the origin at the cheapest rate. */
  private double focus(double x, double y, double z) {
    return cheapest * distance(x, y, z, origin.x(), origin.y(), origin.z());
  }

  private double cross(
      int region, double fx, double fy, double fz, double tx, double ty, double tz) {
    return cost.traverse(
        graph.regionClass(region),
        graph.navigable(region),
        graph.breakTime(region),
        fx,
        fy,
        fz,
        tx,
        ty,
        tz);
  }

  /**
   * Finds the region a cell's estimate should be read from: its own, or for a cell this agent
   * cannot occupy — a boat's air, a body mid-fall — the nearest usable one beneath or beside it.
   */
  private int usableRegionNear(int x, int y, int z) {
    int region = graph.regionAt(x, y, z);
    if (usable(region)) {
      return region;
    }
    for (int dy = 1; dy <= 4; dy++) {
      region = graph.regionAt(x, y - dy, z);
      if (usable(region)) {
        return region;
      }
    }
    int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
    for (int[] offset : around) {
      int nx = x + offset[0];
      int nz = z + offset[2];
      // Only within a column already in hand: this is a fallback, not a reason to fetch.
      if (Math.floorDiv(nx, 16) != Math.floorDiv(x, 16)
          || Math.floorDiv(nz, 16) != Math.floorDiv(z, 16)) {
        continue;
      }
      region = graph.regionAt(nx, y + offset[1], nz);
      if (usable(region)) {
        return region;
      }
    }
    return RegionGraph.NONE;
  }

  private boolean usable(int region) {
    return region != RegionGraph.NONE && cost.usable(graph.regionClass(region));
  }

  private double clamp(double value, int portal, int axis) {
    return Math.max(
        graph.portalBound(portal, axis), Math.min(graph.portalBound(portal, axis + 3), value));
  }

  private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
    double dx = ax - bx;
    double dy = ay - by;
    double dz = az - bz;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /** Grows the label arrays to cover every portal the graph has built so far. */
  private void grow() {
    int size = graph.portals();
    if (size <= g.length) {
      return;
    }
    int grown = Math.max(size, g.length * 2);
    int old = g.length;
    g = Arrays.copyOf(g, grown);
    Arrays.fill(g, old, grown, Double.MAX_VALUE);
    px = Arrays.copyOf(px, grown);
    py = Arrays.copyOf(py, grown);
    pz = Arrays.copyOf(pz, grown);
    state = Arrays.copyOf(state, grown);
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

  /**
   * Returns how many portals the search has settled.
   *
   * @return the expansion count
   */
  public long expansions() {
    return expansions;
  }

  /**
   * Returns the graph the search runs over.
   *
   * @return the graph
   */
  public RegionGraph graph() {
    return graph;
  }

  /** A binary min-heap of (key, portal) pairs, without boxing either. */
  private static final class Heap {

    private double[] keys = new double[256];
    private int[] values = new int[256];
    private int size;

    boolean isEmpty() {
      return size == 0;
    }

    double minKey() {
      return keys[0];
    }

    int minValue() {
      return values[0];
    }

    void push(double key, int value) {
      if (size == keys.length) {
        keys = Arrays.copyOf(keys, size * 2);
        values = Arrays.copyOf(values, size * 2);
      }
      int at = size++;
      while (at > 0) {
        int parent = (at - 1) >> 1;
        if (keys[parent] <= key) {
          break;
        }
        keys[at] = keys[parent];
        values[at] = values[parent];
        at = parent;
      }
      keys[at] = key;
      values[at] = value;
    }

    void pop() {
      double key = keys[--size];
      int value = values[size];
      int at = 0;
      while (true) {
        int child = 2 * at + 1;
        if (child >= size) {
          break;
        }
        if (child + 1 < size && keys[child + 1] < keys[child]) {
          child++;
        }
        if (keys[child] >= key) {
          break;
        }
        keys[at] = keys[child];
        values[at] = values[child];
        at = child;
      }
      keys[at] = key;
      values[at] = value;
    }
  }
}
