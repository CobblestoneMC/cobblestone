/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

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
 * <p><b>Answers are final when given.</b> {@link RegionSearch} only answers once nothing left on
 * its frontier could undercut the answer, so a cell asked about twice gets the same number, and the
 * answers are cached by cell for the fine search's many repeat questions.
 *
 * @param <C> the platform's column of terrain
 */
public final class RegionHeuristic<C> implements HeuristicStrategy {

  /**
   * Cached answers a solve keeps before starting over.
   *
   * <p>Only a bound on memory: dropping the cache costs a recomputation per cell, never a different
   * answer.
   */
  private static final int CACHE_LIMIT = 1 << 20;

  private final Function<DomainRegion<?>, TerrainSource<C>> sourceFor;
  private final Function<DomainRegion<?>, RegionStore<C>> storeFor;
  private final Function<DomainRegion<?>, RegionAgent> agentFor;
  private final double tierOneRate;

  /**
   * Creates a heuristic that reads a terrain source, a store and an agent per solve.
   *
   * <p>Per solve, not per strategy: one strategy is built per navigate, but a trip may have legs in
   * several worlds, and a source bound to the first would answer every later leg from the wrong
   * terrain without ever failing. The target region knows which world its leg is in. The agent too,
   * since what it pays for a kind of terrain can depend on the world.
   *
   * <p>A server's store is its world's, shared by every search, so a column built by any search is
   * read back instead of built again. A store of each solve's own ({@link RegionStore#inMemory})
   * builds every column it needs and forgets them.
   *
   * <p>Estimates are the region search's own answers, unscaled. How far the fine search trusts them
   * is the search's heuristic weight, and nothing else.
   *
   * @param sourceFor the terrain source for a solve's target region, for the columns the store has
   *     to build
   * @param storeFor the store for a solve's target region's world
   * @param agentFor who is travelling, as priced in a solve's target region's world
   * @param tierOneRate the least the agent can pay per block by any movement at all, which Tier 1
   *     prices unsolved legs with: lower than {@link RegionAgent#cheapest()} for an agent who can
   *     move in ways the region layer does not price, such as riding
   */
  public RegionHeuristic(
      Function<DomainRegion<?>, TerrainSource<C>> sourceFor,
      Function<DomainRegion<?>, RegionStore<C>> storeFor,
      Function<DomainRegion<?>, RegionAgent> agentFor,
      double tierOneRate) {
    this.sourceFor = sourceFor;
    this.storeFor = storeFor;
    this.agentFor = agentFor;
    this.tierOneRate = tierOneRate;
  }

  @Override
  public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
    // Tier 1 asks without a solve behind it, and wants a cheap admissible bound.
    return from.distance(target.nearestBoundaryCell(from)) * tierOneRate;
  }

  @Override
  public Solve newSolve(int windowWidth, DomainRegion<?> target) {
    return new Solve(
        target,
        new RegionGraph<>(storeFor.apply(target), sourceFor.apply(target)),
        agentFor.apply(target));
  }

  /** One solve's view: a search seeded on first use, and the answers it has given. */
  public final class Solve implements SolveHeuristic {

    private final DomainRegion<?> target;
    private final RegionGraph<C> graph;
    private final RegionAgent agent;
    private final Map<Cell, Double> answered = new HashMap<>();
    private @Nullable RegionSearch search;

    Solve(DomainRegion<?> target, RegionGraph<C> graph, RegionAgent agent) {
      this.target = target;
      this.graph = graph;
      this.agent = agent;
    }

    /**
     * Returns the solve's region search, for measuring what it did, or {@code null} if nothing has
     * been asked yet.
     *
     * @return the search
     */
    public @Nullable RegionSearch search() {
      return search;
    }

    @Override
    public double seed() {
      return agent.cheapest();
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
      RegionSearch running = searchFrom(from);
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
      RegionSearch running = searchFrom(from);
      double estimate = running.costToGoal(from);
      if (running.takePending() == null) {
        remember(from, estimate);
      }
      return estimate;
    }

    private RegionSearch searchFrom(Cell from) {
      if (search == null) {
        // Seeded at the first cell asked about, which is the solve's start: the search focuses on
        // it, and the goal cell worth aiming at depends on where the route begins.
        search =
            new RegionSearch(
                graph, agent, target.nearestBoundaryCell(from), from, target::contains);
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
