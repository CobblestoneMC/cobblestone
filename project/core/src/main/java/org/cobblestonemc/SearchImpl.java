/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.api.FailureReason;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.api.Step;

/**
 * Orchestrates one search: runs Tier-1 Dijkstra, solves each chosen {@link VirtualPath} with a
 * cooperative {@link Tier2Search}, and re-plans after every solve so alternative routes are
 * reconsidered as true edge costs become known (the anytime recalc loop). Everything runs on the
 * {@link Scheduler}; the search never blocks a thread.
 *
 * <p>Phase-2 note: this re-plans after <i>every</i> edge solve rather than only on a threshold
 * overshoot, and solves each edge to completion rather than pausing mid-solve — a simpler, still
 * terminating and still result-optimal realization of the design's recalc loop.
 *
 * @param <A> the agent type
 * @param <T> the payload type
 * @param <D> the domain type
 */
final class SearchImpl<A extends Agent, T, D extends Domain>
    implements SearchHandle<Position<D>, T> {

  // for logging
  private static final AtomicInteger SEARCH_ID_COUNTER = new AtomicInteger(0);
  private final int searchId = SEARCH_ID_COUNTER.incrementAndGet();

  private final CobblestoneLogger logger;
  private final Scheduler scheduler;
  private final Executor executor;
  private final HeuristicStrategy heuristic;
  private final A agent;
  private final Position<D> origin;
  private final ModesProvider<A, T, D> modes;
  private final List<? extends Restriction<A, D>> restrictions;
  private final SearchSettings settings;
  private final Tier1Graph<T, D> tier1;
  private final long deadlineMillis;

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final CompletableFuture<NavigationResult<Position<D>, T>> future =
      new CompletableFuture<>();

  private GraphPath<Tier1Node<T, D>, Tier1Edge<T, D>> graphPath;
  private boolean
      limitHit; // a Tier-2 solve gave up on the cell limit (memory guard), not a real dead end

  SearchImpl(
      CobblestoneLogger logger,
      Scheduler scheduler,
      HeuristicStrategy heuristic,
      A agent,
      Position<D> origin,
      Destination<DomainRegion<D>> destination,
      ModesProvider<A, T, D> modes,
      List<? extends Transition<T, D>> transitions,
      List<? extends Restriction<A, D>> restrictions,
      SearchSettings settings) {
    this.logger = new ScopedCobblestoneLogger(logger, "search[" + searchId + "]");
    this.scheduler = scheduler;
    this.executor = scheduler.asyncExecutor();
    this.heuristic = heuristic;
    this.agent = agent;
    this.origin = origin;
    this.modes = modes;
    this.restrictions = List.copyOf(restrictions);
    this.settings = settings;

    this.tier1 =
        new Tier1Graph<>(
            origin,
            transitions,
            destination.regions(),
            heuristic,
            settings.tier1UnsolvedPessimism());
    this.deadlineMillis = System.currentTimeMillis() + settings.maxWallClockMillis();

    this.logger.debug("Constructed for agent {} and destination {}", agent, destination);
  }

  void start() {
    logger.trace("Executing async start");
    scheduler.runAsync(this::step);
  }

  @Override
  public CompletableFuture<NavigationResult<Position<D>, T>> future() {
    return future;
  }

  @Override
  public void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      future.complete(new NavigationResult.Failure<>(FailureReason.CANCELLED));
    }
  }

  private void step() {
    if (cancelled.get()) {
      return;
    }
    if (System.currentTimeMillis() > deadlineMillis) {
      finish(new NavigationResult.Failure<>(FailureReason.TIMED_OUT));
      return;
    }
    logger.trace("Executing search step");
    try {
      if (graphPath == null) {
        Optional<GraphPath<Tier1Node<T, D>, Tier1Edge<T, D>>> found =
            tier1.shortestPath(tier1.originNode(), tier1::isGoal);
        if (found.isEmpty()) {
          // If a leg gave up on the cell limit, that — not a genuine disconnect — is why we failed.
          finish(
              new NavigationResult.Failure<>(
                  limitHit ? FailureReason.LIMIT_EXCEEDED : FailureReason.NO_ROUTE));
          return;
        }
        graphPath = found.get();
      }

      List<Tier1Edge<T, D>> legs = graphPath.edges();
      int next = -1;
      int unsolved = 0;
      for (int i = 0; i < legs.size(); i++) {
        if (!legs.get(i).virtualPath().isResolved()) {
          if (next < 0) {
            next = i;
          }
          unsolved++;
        }
      }
      if (next < 0) {
        logger.debug("Solved every leg; the {}-leg route costs {}", legs.size(), graphPath.dist());
        finish(new NavigationResult.Success<>(buildPath(graphPath)));
        return;
      }
      Tier1Edge<T, D> edge = legs.get(next);
      VirtualPath<T, D> virtualPath = edge.virtualPath();
      // One line per step, and the only one at DEBUG: which leg of which route, and what the route
      // is believed to cost while that leg is still an estimate.
      logger.debug(
          "Solving leg {}/{} ({} unsolved) from {} to {}; leg estimated at {} of a {} route",
          next + 1,
          legs.size(),
          unsolved,
          virtualPath.fromCell(),
          virtualPath.targetRegion(),
          virtualPath.cost(heuristic),
          graphPath.dist());
      Tier2Search<A, T, D> tier2 =
          new Tier2Search<>(
              logger,
              agent,
              edge.virtualPath(),
              modes.modesFor(edge.virtualPath().targetRegion()),
              restrictions,
              heuristic,
              settings.maxCellsVisited(),
              settings.runningAverageWidth(),
              settings.heuristicWeight(),
              cancelled::get,
              executor,
              deadlineMillis);
      tier2
          .solve()
          .whenCompleteAsync(
              (result, error) -> {
                if (cancelled.get()) {
                  return;
                }
                if (error != null) {
                  finish(new NavigationResult.Error<>(error));
                  return;
                }
                switch (result) {
                  case Tier2Result.Failed<T, D> v -> {
                    if (v.outcome() == Tier2Result.FailureOutcome.LIMIT_EXCEEDED) {
                      limitHit = true;
                    }
                    virtualPath.markInfeasible();
                  }
                  case Tier2Result.Solved<T, D> v -> {
                    virtualPath.solve(v.steps(), v.cost());
                  }
                }
                graphPath = null; // re-plan with the now-known edge cost
                step();
              },
              executor);
    } catch (Throwable throwable) {
      finish(new NavigationResult.Error<>(throwable));
    }
  }

  private Path<Position<D>, T> buildPath(GraphPath<Tier1Node<T, D>, Tier1Edge<T, D>> path) {
    List<Step<Position<D>, T>> steps = new ArrayList<>();
    for (Tier1Edge<T, D> edge : path.edges()) {
      for (RawStep<T, D> raw : edge.virtualPath().solvedSteps()) {
        steps.add(new Step<>(raw.position(), raw.stepCost(), raw.stepTime(), raw.payload()));
      }
      Tier1Node<T, D> target = edge.target();
      if (target instanceof Tier1Node.AtTransition<T, D> atTransition) {
        Transition<T, D> transition = atTransition.transition();
        steps.add(
            new Step<>(
                transition.destination(),
                transition.cost(),
                transition.time(),
                transition.payload()));
      }
    }
    return new Path<>(origin, steps);
  }

  private void finish(NavigationResult<Position<D>, T> result) {
    future.complete(result);
  }
}
