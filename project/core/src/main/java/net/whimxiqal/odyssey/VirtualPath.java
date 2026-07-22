/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

import java.util.List;

/**
 * A mutable Tier-1 edge: an <i>unsolved</i> same-domain hop from a cell to a target region, whose
 * cost starts as an optimistic estimate and is later resolved by a Tier-2 A* solve into a concrete
 * step sequence (or proven infeasible).
 *
 * <p>Instances are memoized by the Tier-1 graph and reused across Dijkstra re-plans, so a solve
 * result persists: once {@link #solve} or {@link #markInfeasible} is called the cost is fixed.
 *
 * @param <T> the payload type
 * @param <D> the domain type
 */
final class VirtualPath<T, D extends Domain> {

  private enum Status { UNSOLVED, SOLVED, INFEASIBLE }

  private final Cell fromCell;
  private final D domain;
  private final DomainRegion<D> targetRegion;
  private final TraversalState state;

  private Status status = Status.UNSOLVED;
  private List<RawStep<T, D>> solvedSteps = List.of();
  private double trueCost;

  VirtualPath(Cell fromCell, D domain, DomainRegion<D> targetRegion, TraversalState state) {
    this.fromCell = fromCell;
    this.domain = domain;
    this.targetRegion = targetRegion;
    this.state = state;
  }

  Cell fromCell() {
    return fromCell;
  }

  D domain() {
    return domain;
  }

  DomainRegion<D> targetRegion() {
    return targetRegion;
  }

  TraversalState state() {
    return state;
  }

  boolean isSolved() {
    return status == Status.SOLVED;
  }

  boolean isResolved() {
    return status != Status.UNSOLVED;
  }

  List<RawStep<T, D>> solvedSteps() {
    return solvedSteps;
  }

  /**
   * Returns the current cost used by Tier-1 Dijkstra: the true cost once solved, {@code +∞} if
   * infeasible, otherwise the optimistic estimate from {@code heuristic}.
   */
  double cost(HeuristicStrategy heuristic) {
    return switch (status) {
      case SOLVED -> trueCost;
      case INFEASIBLE -> Double.POSITIVE_INFINITY;
      case UNSOLVED -> heuristic.estimate(fromCell, targetRegion, state);
    };
  }

  void solve(List<RawStep<T, D>> steps, double cost) {
    this.solvedSteps = List.copyOf(steps);
    this.trueCost = cost;
    this.status = Status.SOLVED;
  }

  void markInfeasible() {
    this.status = Status.INFEASIBLE;
    this.solvedSteps = List.of();
    this.trueCost = Double.POSITIVE_INFINITY;
  }
}
