/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import net.whimxiqal.odyssey.api.Agent;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.Mode;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.Scheduler;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.api.Transition;

/**
 * Orchestrates one search: runs Tier-1 Dijkstra, solves each chosen {@link VirtualPath} with a
 * cooperative {@link Tier2Search}, and re-plans after every solve so alternative routes are
 * reconsidered as true edge costs become known (the anytime recalc loop). Everything runs on the
 * {@link Scheduler}; the search never blocks a thread.
 *
 * <p>Phase-2 note: this re-plans after <i>every</i> edge solve rather than only on a threshold
 * overshoot, and solves each edge to completion rather than pausing mid-solve — a simpler, still
 * terminating and still result-optimal realization of the design's recalc loop. The
 * {@code tier1RecalcThreshold} knob is therefore not yet consulted.
 *
 * @param <A> the agent type
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
final class SearchImpl<A extends Agent, T extends Enum<T>, I, D extends Domain>
    implements SearchHandle<T, I, D> {

  private final Scheduler scheduler;
  private final Executor executor;
  private final HeuristicStrategy heuristic;
  private final A agent;
  private final List<? extends Mode<A, T, I, D>> modes;
  private final SearchSettings settings;
  private final Tier1Graph<T, I, D> tier1;
  private final long deadlineMillis;

  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final CompletableFuture<NavigationResult<T, I, D>> future = new CompletableFuture<>();

  private GraphPath<Tier1Node<T, I, D>, Tier1Edge<T, I, D>> graphPath;

  SearchImpl(
      Scheduler scheduler,
      HeuristicStrategy heuristic,
      A agent,
      Position<D> origin,
      Destination<D> destination,
      List<? extends Mode<A, T, I, D>> modes,
      List<? extends Transition<T, I, D>> transitions,
      SearchSettings settings) {
    this.scheduler = scheduler;
    this.executor = scheduler.asyncExecutor();
    this.heuristic = heuristic;
    this.agent = agent;
    this.modes = List.copyOf(modes);
    this.settings = settings;
    this.tier1 = new Tier1Graph<>(origin, transitions, destination.regions(), heuristic);
    this.deadlineMillis = System.currentTimeMillis() + settings.maxWallClockMillis();
  }

  void start() {
    scheduler.runAsync(this::step);
  }

  @Override
  public CompletableFuture<NavigationResult<T, I, D>> future() {
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
    try {
      if (graphPath == null) {
        Optional<GraphPath<Tier1Node<T, I, D>, Tier1Edge<T, I, D>>> found =
            tier1.shortestPath(tier1.originNode(), tier1::isGoal);
        if (found.isEmpty()) {
          finish(new NavigationResult.Failure<>(FailureReason.NO_ROUTE));
          return;
        }
        graphPath = found.get();
      }
      Tier1Edge.VirtualPathEdge<T, I, D> edge = firstUnsolvedEdge(graphPath);
      if (edge == null) {
        finish(new NavigationResult.Success<>(buildPath(graphPath)));
        return;
      }
      Tier2Search<A, T, I, D> tier2 = new Tier2Search<>(
          agent, edge.virtualPath(), modes, heuristic, settings.maxCellsVisited(), cancelled::get, executor);
      VirtualPath<T, I, D> virtualPath = edge.virtualPath();
      tier2.solve().whenCompleteAsync((result, error) -> {
        if (cancelled.get()) {
          return;
        }
        if (error != null) {
          finish(new NavigationResult.Failure<>(FailureReason.ERROR));
          return;
        }
        if (result.solved()) {
          virtualPath.solve(result.steps(), result.cost());
        } else {
          virtualPath.markInfeasible();
        }
        graphPath = null; // re-plan with the now-known edge cost
        step();
      }, executor);
    } catch (Throwable throwable) {
      finish(new NavigationResult.Failure<>(FailureReason.ERROR));
    }
  }

  private Tier1Edge.VirtualPathEdge<T, I, D> firstUnsolvedEdge(
      GraphPath<Tier1Node<T, I, D>, Tier1Edge<T, I, D>> path) {
    for (Tier1Edge<T, I, D> edge : path.edges()) {
      if (edge instanceof Tier1Edge.VirtualPathEdge<T, I, D> vpe && !vpe.virtualPath().isResolved()) {
        return vpe;
      }
    }
    return null;
  }

  private Path<T, I, D> buildPath(GraphPath<Tier1Node<T, I, D>, Tier1Edge<T, I, D>> path) {
    List<Step<T, I, D>> steps = new ArrayList<>();
    double global = 0.0;
    for (Tier1Edge<T, I, D> edge : path.edges()) {
      if (!(edge instanceof Tier1Edge.VirtualPathEdge<T, I, D> vpe)) {
        continue; // sink edge contributes nothing
      }
      for (RawStep<T, I, D> raw : vpe.virtualPath().solvedSteps()) {
        global += raw.stepCost();
        steps.add(new Step<>(raw.cell(), raw.domain(), global, raw.stepType(), raw.instruction()));
      }
      Transition<T, I, D> target = vpe.targetTransition();
      if (!(target instanceof SyntheticTransition<T, I, D>)) {
        global += target.cost();
        steps.add(new Step<>(
            target.destination().cell(), target.destination().domain(), global,
            target.stepType(), target.instruction()));
      }
    }
    return new PathImpl<>(steps, global);
  }

  private void finish(NavigationResult<T, I, D> result) {
    future.complete(result);
  }
}
