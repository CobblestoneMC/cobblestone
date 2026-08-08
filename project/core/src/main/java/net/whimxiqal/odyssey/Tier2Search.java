/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * A single-domain A* solve for one {@link VirtualPath}, implemented as a
 * <b>resumable</b> object so
 * it can run cooperatively: {@link #advance()} consumes cache-hit movements in
 * a tight synchronous
 * loop and only <i>parks</i> (freeing its worker) when a mode's
 * {@link FutureOr} is pending, then
 * resumes on the {@link Executor} once the block(s) arrive.
 *
 * <p>
 * The visited set is keyed on {@code (cell, TraversalState)}; the heuristic is
 * consistent, so a
 * closed-set A* returns least-cost paths for the explored terrain. Completes
 * {@link #solve()}'s
 * future with a {@link Tier2Result}.
 *
 * @param <A> the agent type
 * @param <T> the payload type
 * @param <D> the domain type
 */
final class Tier2Search<A extends Agent, T, D extends Domain> {

  private final OdysseyLogger logger;
  private final A agent;
  private final D domain;

  private final DomainRegion<D> target;
  private final List<? extends Mode<A, T, D>> modes;
  private final SolveHeuristic heuristic;
  private final int maxCellsVisited;
  private final BooleanSupplier cancelled;
  private final Executor executor;
  private final Map<CellState, Double> bestCosts = new HashMap<>();
  private final Map<CellState, Came<T>> cameFrom = new HashMap<>();
  private final Set<CellState> closed = new HashSet<>();
  private final PriorityQueue<OpenEntry> open = new PriorityQueue<>(
      (a, b) -> Double.compare(a.estimatedTotalCost(), b.estimatedTotalCost()));
  private final CompletableFuture<Tier2Result<T, D>> result = new CompletableFuture<>();

  private PendingExpansion<T> pendingExpansion;

  Tier2Search(
          OdysseyLogger logger,
          A agent,
      VirtualPath<T, D> virtualPath,
      List<? extends Mode<A, T, D>> modes,
      HeuristicStrategy heuristic,
      int maxCellsVisited,
      int runningAverageWidth,
      BooleanSupplier cancelled,
      Executor executor) {
    this.logger = logger;
    this.agent = agent;
    this.domain = virtualPath.domain();
    this.target = virtualPath.targetRegion();
    this.modes = modes;
    this.heuristic = heuristic.newSolve(runningAverageWidth);
    this.maxCellsVisited = maxCellsVisited;
    this.cancelled = cancelled;
    this.executor = executor;

    CellState start = new CellState(virtualPath.fromCell(), virtualPath.state());
    bestCosts.put(start, 0.0);
    open.add(new OpenEntry(start, 0.0, this.heuristic.estimate(start.cell(), target, start.state())));
  }

  CompletableFuture<Tier2Result<T, D>> solve() {
    advance();
    return result;
  }

  private void advance() {
    try {
      while (true) {
        if (cancelled.getAsBoolean()) {
          return; // abandoned; the outer search has already completed with CANCELLED
        }
        if (pendingExpansion != null) {
          PendingExpansion<T> expansion = pendingExpansion;
          pendingExpansion = null;
          relax(expansion.node(), expansion.g(), unwrap(expansion.results()));
          continue;
        }
        if (open.isEmpty()) {
          logger.debug("Tier2Search(agent:{},target:{}) failed: open set is empty", agent, target);
          result.complete(Tier2Result.unreachable());
          return;
        }
        OpenEntry entry = open.poll();
        CellState node = entry.key();
        if (closed.contains(node) || entry.currentCost() > bestCosts.getOrDefault(node, Double.POSITIVE_INFINITY)) {
          continue; // stale duplicate
        }
        closed.add(node);
        Came<T> reachedBy = cameFrom.get(node);
        if (reachedBy != null) {
          // Feed the real cost of the committed step to the (possibly adaptive) heuristic.
          heuristic.observe(reachedBy.movement().cost(), reachedBy.parent().cell().distance(node.cell()));
        }
        if (target.contains(node.cell())) {
          logger.debug("Tier2Search(agent:{},target:{}) solved. visited:{}", agent, target, closed.size());
          result.complete(Tier2Result.solved(reconstruct(node), entry.currentCost()));
          return;
        }
        if (closed.size() > maxCellsVisited) {
          logger.debug("Tier2Search(agent:{},target:{}) visited cells ({}) > max ({})", agent, target, closed.size(), maxCellsVisited);
          result.complete(Tier2Result.limitExceeded());
          return;
        }

        List<FutureOr<Collection<Movement<T>>>> results = new ArrayList<>(modes.size());
        boolean anyPending = false;
        for (Mode<A, T, D> mode : modes) {
          FutureOr<Collection<Movement<T>>> movements = mode.step(agent, node.cell(), domain, node.state());
          results.add(movements);
          anyPending |= !movements.isImmediate();
        }
        if (anyPending) {
          logger.trace("Tier2Search(agent:{},target:{}) parked search due to pending modes", agent, target);
          park(node, entry.currentCost(), results);
          return;
        }
        relax(node, entry.currentCost(), unwrap(results));
      }
    } catch (Throwable throwable) {
      result.completeExceptionally(throwable);
    }
  }

  private void park(CellState node, double nodeCost, List<FutureOr<Collection<Movement<T>>>> results) {
    pendingExpansion = new PendingExpansion<>(node, nodeCost, results);
    List<CompletableFuture<?>> pending = new ArrayList<>();
    for (FutureOr<Collection<Movement<T>>> movements : results) {
      if (!movements.isImmediate()) {
        pending.add(movements.future());
      }
    }
    CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
        .whenCompleteAsync((ignored, error) -> {
          if (error != null) {
            result.completeExceptionally(error);
          } else {
            advance();
          }
        }, executor);
  }

  private List<Movement<T>> unwrap(List<FutureOr<Collection<Movement<T>>>> results) {
    List<Movement<T>> movements = new ArrayList<>();
    for (FutureOr<Collection<Movement<T>>> futureOr : results) {
      Collection<Movement<T>> value = futureOr.value();
      if (value != null) {
        movements.addAll(value);
      }
    }
    return movements;
  }

  private void relax(CellState node, double nodeCost, List<Movement<T>> movements) {
    for (Movement<T> movement : movements) {
      CellState neighbor = new CellState(movement.cell(), movement.state());
      double tentative = nodeCost + movement.cost();
      if (tentative < bestCosts.getOrDefault(neighbor, Double.POSITIVE_INFINITY)) {
        bestCosts.put(neighbor, tentative);
        cameFrom.put(neighbor, new Came<>(node, movement));
        open.add(new OpenEntry(neighbor, tentative,
            tentative + heuristic.estimate(movement.cell(), target, movement.state())));
      }
    }
  }

  private List<RawStep<T, D>> reconstruct(CellState goal) {
    Deque<RawStep<T, D>> steps = new ArrayDeque<>();
    CellState cursor = goal;
    while (cameFrom.containsKey(cursor)) {
      Came<T> came = cameFrom.get(cursor);
      Movement<T> movement = came.movement();
      steps.addFirst(new RawStep<>(
          new Position<>(cursor.cell(), domain), movement.cost(), movement.time(), movement.payload()));
      cursor = came.parent();
    }
    return new ArrayList<>(steps);
  }

  private record CellState(Cell cell, TraversalState state) {
  }

  private record OpenEntry(CellState key, double currentCost, double estimatedTotalCost) {
  }

  private record Came<T>(CellState parent, Movement<T> movement) {
  }

  private record PendingExpansion<T>(
      CellState node, double g, List<FutureOr<Collection<Movement<T>>>> results) {
  }
}
