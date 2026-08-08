/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

/**
 * A per-solve, possibly-stateful heuristic instance created by {@link HeuristicStrategy#newSolve(int)}
 * for a single Tier-2 A* solve. Unlike the stateless {@link HeuristicStrategy} (which Tier-1 uses as
 * an admissible lower bound), a solve heuristic may adapt to the costs actually seen during the
 * search — e.g. the running-average heuristic tightens its estimate toward the real per-block cost of
 * the terrain being traversed, trading admissibility for a far smaller explored frontier.
 */
public interface SolveHeuristic {

  /**
   * Estimates the remaining cost from {@code from} into {@code target} while in {@code state}.
   *
   * @param from the current cell
   * @param target the region being sought
   * @param state the current traversal state
   * @return a cost estimate in seconds (not necessarily a lower bound)
   */
  double estimate(Cell from, DomainRegion<?> target, TraversalState state);

  /**
   * Feeds back the real cost of a step the search committed to, so an adaptive heuristic can update.
   * A stateless heuristic ignores this.
   *
   * @param stepCost the step's cost in seconds
   * @param blocks the step's length in blocks (used to derive a per-block cost)
   */
  void observe(double stepCost, double blocks);
}
