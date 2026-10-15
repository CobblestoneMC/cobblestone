/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.HashMap;
import java.util.List;
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

  private final Function<DomainRegion<?>, RegionGraph.ChunkColumns> columnsFor;
  private final @Nullable Function<DomainRegion<?>, RegionStore> storeFor;
  private final RegionCost cost;
  private final double detourRatio;
  private final double tierOneRate;
  private final boolean keepSearches;
  private final List<RegionSearch> searches = new java.util.ArrayList<>();

  /**
   * Creates a heuristic over a single terrain source.
   *
   * @param columns where chunk columns come from
   * @param cost the agent
   */
  public RegionHeuristic(RegionGraph.ChunkColumns columns, RegionCost cost) {
    this(region -> columns, null, cost, RegionGraph.DEFAULT_DETOUR_RATIO);
  }

  /**
   * Creates a heuristic that builds a terrain source per solve, reading built columns from a shared
   * store, as a server's do.
   *
   * <p>Per solve, not per strategy: one strategy is built per navigate, but a trip may have legs in
   * several worlds, and a source bound to the first would answer every later leg from the wrong
   * terrain without ever failing. The target region knows which world its leg is in.
   *
   * <p>Without a store each solve builds every column it needs and forgets them; with one, a column
   * built by any search — or before the server last restarted — is read back instead.
   *
   * <p>Estimates are the region search's own answers, unscaled. How far the fine search trusts them
   * is the search's heuristic weight, and nothing else.
   *
   * @param columnsFor builds a chunk source for a solve's target region, for the columns the store
   *     has to build
   * @param storeFor the store for a solve's target region's world, or {@code null} for a store of
   *     each solve's own
   * @param cost the agent
   * @param detourRatio the fill's detour ratio, for a store of each solve's own; a shared store
   *     carries its own; see {@link RegionGraph#DEFAULT_DETOUR_RATIO}
   */
  public RegionHeuristic(
      Function<DomainRegion<?>, RegionGraph.ChunkColumns> columnsFor,
      @Nullable Function<DomainRegion<?>, RegionStore> storeFor,
      RegionCost cost,
      double detourRatio) {
    this(columnsFor, storeFor, cost, detourRatio, cost.cheapest(), false);
  }

  /**
   * Creates a heuristic with its Tier-1 bound given, and whether to keep its searches.
   *
   * @param columnsFor builds a chunk source for a solve's target region
   * @param storeFor the store for a solve's target region's world, or {@code null} for a store of
   *     each solve's own
   * @param cost the agent
   * @param detourRatio the fill's detour ratio, for a store of each solve's own
   * @param tierOneRate the least the agent can pay per block by any movement at all, which Tier 1
   *     prices unsolved legs with: lower than {@link RegionCost#cheapest()} for an agent who can
   *     ride or fall, since the region layer prices neither as a medium
   * @param keepSearches whether to keep every solve's search for {@link #searches()}, which holds
   *     each one's graph for the strategy's life; for measuring, never on a server
   */
  public RegionHeuristic(
      Function<DomainRegion<?>, RegionGraph.ChunkColumns> columnsFor,
      @Nullable Function<DomainRegion<?>, RegionStore> storeFor,
      RegionCost cost,
      double detourRatio,
      double tierOneRate,
      boolean keepSearches) {
    this.columnsFor = columnsFor;
    this.storeFor = storeFor;
    this.cost = cost;
    this.detourRatio = detourRatio;
    this.tierOneRate = tierOneRate;
    this.keepSearches = keepSearches;
  }

  @Override
  public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
    // Tier 1 asks without a solve behind it, and wants a cheap admissible bound.
    return from.distance(target.nearestBoundaryCell(from)) * tierOneRate;
  }

  @Override
  public SolveHeuristic newSolve(int windowWidth, DomainRegion<?> target) {
    RegionGraph.ChunkColumns columns = columnsFor.apply(target);
    RegionStore store =
        storeFor == null
            ? RegionStore.inMemory(columns.minY(), columns.maxY(), detourRatio)
            : storeFor.apply(target);
    return new Solve(target, new RegionGraph(store, columns));
  }

  /**
   * Returns the searches this strategy's solves have run, one per solve that asked anything, if it
   * was made to keep them.
   *
   * <p>For diagnostics: the benchmark reads what each spent on building terrain against searching
   * it. Kept for the strategy's life, which is one navigate.
   *
   * @return the searches, oldest first
   */
  public List<RegionSearch> searches() {
    return List.copyOf(searches);
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
        return known;
      }
      // Reached only when something estimates without preparing; answer, but cache only a real
      // answer.
      RegionSearch running = search(from);
      double estimate = running.costToGoal(from);
      if (running.takePending() == null) {
        remember(from, estimate);
      }
      return estimate;
    }

    private RegionSearch search(Cell from) {
      if (search == null) {
        // Seeded at the first cell asked about, which is the solve's start: the search focuses on
        // it, and the goal cell worth aiming at depends on where the route begins.
        search =
            new RegionSearch(graph, cost, target.nearestBoundaryCell(from), from, target::contains);
        if (keepSearches) {
          searches.add(search);
        }
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
