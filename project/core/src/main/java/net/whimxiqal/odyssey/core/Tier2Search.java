/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

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
import net.whimxiqal.odyssey.api.Agent;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.DomainRegion;
import net.whimxiqal.odyssey.api.FutureOr;
import net.whimxiqal.odyssey.api.Mode;
import net.whimxiqal.odyssey.api.Movement;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.TraversalState;

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
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
final class Tier2Search<A extends Agent, T extends Enum<T>, I, D extends Domain> {

  private final A agent;
  private final D domain;

  private final DomainRegion<D> target;
  private final List<? extends Mode<A, T, I, D>> modes;
  private final net.whimxiqal.odyssey.api.HeuristicStrategy heuristic;
  private final int maxCellsVisited;
  private final BooleanSupplier cancelled;
  private final Executor executor;
  private final Map<CellState, Double> bestCosts = new HashMap<>();
  private final Map<CellState, Came<T, I>> cameFrom = new HashMap<>();
  private final Set<CellState> closed = new HashSet<>();
  private final PriorityQueue<OpenEntry> open = new PriorityQueue<>(
      (a, b) -> Double.compare(a.estimatedTotalCost(), b.estimatedTotalCost()));
  private final CompletableFuture<Tier2Result<T, I, D>> result = new CompletableFuture<>();

  private int cellsVisited;
  private PendingExpansion<T, I> pendingExpansion;

  Tier2Search(
      A agent,
      VirtualPath<T, I, D> virtualPath,
      List<? extends Mode<A, T, I, D>> modes,
      net.whimxiqal.odyssey.api.HeuristicStrategy heuristic,
      int maxCellsVisited,
      BooleanSupplier cancelled,
      Executor executor) {
    this.agent = agent;
    this.domain = virtualPath.domain();
    this.target = virtualPath.targetRegion();
    this.modes = modes;
    this.heuristic = heuristic;
    this.maxCellsVisited = maxCellsVisited;
    this.cancelled = cancelled;
    this.executor = executor;

    CellState start = new CellState(virtualPath.fromCell(), virtualPath.state());
    bestCosts.put(start, 0.0);
    open.add(new OpenEntry(start, 0.0, heuristic.estimate(start.cell(), target, start.state())));
  }

  CompletableFuture<Tier2Result<T, I, D>> solve() {
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
          PendingExpansion<T, I> expansion = pendingExpansion;
          pendingExpansion = null;
          relax(expansion.node(), expansion.g(), unwrap(expansion.results()));
          continue;
        }
        if (open.isEmpty()) {
          result.complete(Tier2Result.unreachable());
          return;
        }
        OpenEntry entry = open.poll();
        CellState node = entry.key();
        if (closed.contains(node) || entry.currentCost() > bestCosts.getOrDefault(node, Double.POSITIVE_INFINITY)) {
          continue; // stale duplicate
        }
        closed.add(node);
        if (target.contains(node.cell())) {
          result.complete(Tier2Result.solved(reconstruct(node), entry.currentCost()));
          return;
        }
        if (++cellsVisited > maxCellsVisited) {
          result.complete(Tier2Result.unreachable());
          return;
        }

        List<FutureOr<Collection<Movement<T, I>>>> results = new ArrayList<>(modes.size());
        boolean anyPending = false;
        for (Mode<A, T, I, D> mode : modes) {
          FutureOr<Collection<Movement<T, I>>> movements = mode.step(agent, node.cell(), domain, node.state());
          results.add(movements);
          anyPending |= !movements.isImmediate();
        }
        if (anyPending) {
          park(node, entry.currentCost(), results);
          return;
        }
        relax(node, entry.currentCost(), unwrap(results));
      }
    } catch (Throwable throwable) {
      result.completeExceptionally(throwable);
    }
  }

  private void park(CellState node, double nodeCost, List<FutureOr<Collection<Movement<T, I>>>> results) {
    pendingExpansion = new PendingExpansion<>(node, nodeCost, results);
    List<CompletableFuture<?>> pending = new ArrayList<>();
    for (FutureOr<Collection<Movement<T, I>>> movements : results) {
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

  private List<Movement<T, I>> unwrap(List<FutureOr<Collection<Movement<T, I>>>> results) {
    List<Movement<T, I>> movements = new ArrayList<>();
    for (FutureOr<Collection<Movement<T, I>>> futureOr : results) {
      Collection<Movement<T, I>> value = futureOr.value();
      if (value != null) {
        movements.addAll(value);
      }
    }
    return movements;
  }

  private void relax(CellState node, double nodeCost, List<Movement<T, I>> movements) {
    for (Movement<T, I> movement : movements) {
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

  private List<RawStep<T, I, D>> reconstruct(CellState goal) {
    Deque<RawStep<T, I, D>> steps = new ArrayDeque<>();
    CellState cursor = goal;
    while (cameFrom.containsKey(cursor)) {
      Came<T, I> came = cameFrom.get(cursor);
      Movement<T, I> movement = came.movement();
      steps
          .addFirst(new RawStep<>(new Position<>(cursor.cell(), domain), movement.cost(), movement.stepType(),
              movement.instruction()));
      cursor = came.parent();
    }
    return new ArrayList<>(steps);
  }

  private record CellState(Cell cell, TraversalState state) {
  }

  private record OpenEntry(CellState key, double currentCost, double estimatedTotalCost) {
  }

  private record Came<T extends Enum<T>, I>(CellState parent, Movement<T, I> movement) {
  }

  private record PendingExpansion<T extends Enum<T>, I>(
      CellState node, double g, List<FutureOr<Collection<Movement<T, I>>>> results) {
  }
}
