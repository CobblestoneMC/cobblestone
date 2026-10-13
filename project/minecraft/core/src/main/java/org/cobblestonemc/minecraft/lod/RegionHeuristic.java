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
import java.util.function.Function;
import org.cobblestonemc.Cell;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.HeuristicStrategy;
import org.cobblestonemc.SolveHeuristic;
import org.cobblestonemc.api.TraversalState;
import org.jetbrains.annotations.Nullable;

/**
 * The fine search's heuristic, answered from a {@link RegionGraph} by a {@link RegionSearch}.
 *
 * <p>Instead of extrapolating from the steps already taken, which is what makes a running average
 * drink a river that does not go anywhere, the estimate comes from the terrain ahead: every
 * connected patch of ground, water, rock and air, and how they join. An estimate is a route through
 * those pieces, priced continuously from the cell asked about.
 *
 * <p>It replaced a heuristic that summarized each 16³ section as one node priced from how much of
 * it each medium covered. That could not tell whether the open half of a section reached the other
 * side, and measured at a quarter to a half of the true cost underground.
 *
 * <p><b>Answers are final when given.</b> {@link RegionSearch} only answers once nothing left on
 * its frontier could undercut the answer, so a cell asked about twice gets the same number, and the
 * answers are cached by cell for the fine search's many repeat questions.
 */
public final class RegionHeuristic implements HeuristicStrategy {

  /**
   * Cached answers a solve keeps before starting over.
   *
   * <p>Only a bound on memory: dropping the cache costs a recomputation per cell, never a different
   * answer.
   */
  private static final int CACHE_LIMIT = 1 << 20;

  /**
   * The factor production applies to every estimate.
   *
   * <p>Below 1 because the unscaled estimate is already close: {@code bench verify} puts it at
   * 0.7–0.95 of the true cost and never above, and a weight-2 search on an estimate that close
   * commits to the first route it likes. Over 58 runs at weight 2.0, against the best path any
   * configuration found: 1.0 expands 434 nodes (geometric mean) for paths +4.9% over; 0.85 expands
   * 685 for +2.6%; 0.7 expands 1,934 for +0.6% and fails a route. The section-coverage heuristic it
   * replaced expanded 1,913 for +5.0% and failed 8.
   */
  public static final double DEFAULT_SCALE = 0.85;

  private final Function<DomainRegion<?>, RegionGraph.ChunkColumns> columnsFor;
  private final RegionCost cost;
  private final double scale;
  private final double detourRatio;

  /**
   * Creates a heuristic over a single terrain source.
   *
   * @param columns where chunk columns come from
   * @param cost the agent
   */
  public RegionHeuristic(RegionGraph.ChunkColumns columns, RegionCost cost) {
    this(region -> columns, cost, DEFAULT_SCALE);
  }

  /**
   * Creates a heuristic that builds a terrain source per solve, scaling every estimate.
   *
   * <p>Per solve, not per strategy: one strategy is built per navigate, but a trip may have legs in
   * several worlds, and a source bound to the first would answer every later leg from the wrong
   * terrain without ever failing. The target region knows which world its leg is in.
   *
   * @param columnsFor builds a terrain source for a solve's target region
   * @param cost the agent
   * @param scale a factor on every estimate; below 1 leaves the weighted search room to explore
   */
  public RegionHeuristic(
      Function<DomainRegion<?>, RegionGraph.ChunkColumns> columnsFor,
      RegionCost cost,
      double scale) {
    this(columnsFor, cost, scale, RegionGraph.DEFAULT_DETOUR_RATIO);
  }

  /**
   * Creates a heuristic with every tuning knob given, for measuring them.
   *
   * @param columnsFor builds a terrain source for a solve's target region
   * @param cost the agent
   * @param scale a factor on every estimate
   * @param detourRatio how far a region may bend before the graph splits it; see {@link
   *     RegionGraph#DEFAULT_DETOUR_RATIO}
   */
  public RegionHeuristic(
      Function<DomainRegion<?>, RegionGraph.ChunkColumns> columnsFor,
      RegionCost cost,
      double scale,
      double detourRatio) {
    this.columnsFor = columnsFor;
    this.cost = cost;
    this.scale = scale;
    this.detourRatio = detourRatio;
  }

  @Override
  public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
    // Tier 1 asks without a solve behind it, and wants a cheap admissible bound.
    return from.distance(target.nearestBoundaryCell(from)) * cost.cheapest();
  }

  @Override
  public SolveHeuristic newSolve(int windowWidth, DomainRegion<?> target) {
    return new Solve(target, new RegionGraph(columnsFor.apply(target), detourRatio));
  }

  /** One solve's view: a search seeded on first use, and the answers it has given. */
  private final class Solve implements SolveHeuristic {

    private final DomainRegion<?> target;
    private final RegionGraph graph;
    private final Map<Cell, Double> answered = new HashMap<>();
    private RegionSearch search;

    Solve(DomainRegion<?> target, RegionGraph graph) {
      this.target = target;
      this.graph = graph;
    }

    @Override
    public double seed() {
      return cost.cheapest();
    }

    @Override
    public double advance(double trailAverage, double stepCost, double blocks) {
      return trailAverage; // the estimate comes from the terrain ahead, not the trail behind
    }

    @Override
    public @Nullable CompletableFuture<Void> prepare(Cell from) {
      if (answered.containsKey(from)) {
        return null;
      }
      RegionSearch running = search(from);
      double estimate = running.costToGoal(from);
      CompletableFuture<Void> waiting = running.takePending();
      if (waiting != null) {
        return waiting; // nothing cached: the answer was a placeholder
      }
      remember(from, estimate);
      return null;
    }

    @Override
    public double estimate(Cell from, double distance, TraversalState state, double trailAverage) {
      Double known = answered.get(from);
      if (known != null) {
        return scale * known;
      }
      // Reached only when something estimates without preparing; answer, but cache only a real
      // answer.
      RegionSearch running = search(from);
      double estimate = running.costToGoal(from);
      if (running.takePending() == null) {
        remember(from, estimate);
      }
      return scale * estimate;
    }

    private RegionSearch search(Cell from) {
      if (search == null) {
        // Seeded at the first cell asked about, which is the solve's start: the search focuses on
        // it, and the goal cell worth aiming at depends on where the route begins.
        search = new RegionSearch(graph, cost, target.nearestBoundaryCell(from), from);
      }
      return search;
    }

    private void remember(Cell from, double estimate) {
      if (answered.size() >= CACHE_LIMIT) {
        answered.clear();
      }
      answered.put(from, estimate);
    }
  }
}
