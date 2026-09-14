/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import org.cobblestonemc.api.TraversalState;

/**
 * A heuristic for a single Tier-2 A* solve, created by {@link HeuristicStrategy#newSolve(int,
 * DomainRegion)}. Unlike the stateless {@link HeuristicStrategy} (which Tier-1 uses as an
 * admissible lower bound), a solve heuristic may adapt to the costs actually seen — e.g. the
 * running-average heuristic tightens its estimate toward the real per-block cost of the terrain
 * being crossed, trading admissibility for a far smaller explored frontier.
 *
 * <p><b>The adaptation is per-cell, not per-solve.</b> Each cell carries a <i>trail average</i>:
 * the average per-block cost of the steps leading to it, which the search inherits down the search
 * tree via {@link #advance} and hands back to {@link #estimate}. So a cell three blocks into a
 * mountain is priced as if the rest of its journey were also through rock, while a cell on the
 * grass beside it is priced as grass — which is the whole point, since the two are otherwise
 * separated by less than a second of {@code f} across a two-thousand-block journey and A* would
 * explore both exhaustively.
 *
 * <p>Implementations are consulted from one solve's worker at a time but hold no mutable state of
 * their own: the state lives on the cells. That also means the estimate for a cell is a property of
 * that cell's own history rather than of whatever the search happened to expand most recently.
 */
public interface SolveHeuristic {

  /**
   * Returns the trail average the start cell carries, before any step has been taken.
   *
   * @return the seed per-block cost, in seconds per block
   */
  double seed();

  /**
   * Folds one step into the trail average, giving the average carried by the cell the step leads
   * to. A step covering several blocks (a long fall) moves the average as much as that many
   * single-block steps would; a zero-length step (a transition in place) leaves it untouched.
   *
   * @param trailAverage the average carried by the cell the step leaves from
   * @param stepCost the step's cost in seconds
   * @param blocks the step's length in blocks
   * @return the average carried by the cell the step arrives at
   */
  double advance(double trailAverage, double stepCost, double blocks);

  /**
   * Estimates the remaining cost from {@code from} into the solve's target while in {@code state},
   * scaled by the trail average {@code from} carries.
   *
   * <p>The target was bound when this solve heuristic was created, and {@code distance} is {@code
   * from} already projected onto it — the search needs that projection for its own weighting, and a
   * composite region is not cheap to project onto twice per relaxed edge. {@code from} is still
   * passed for a heuristic whose estimate is a function of position rather than of distance alone.
   *
   * @param from the current cell
   * @param distance the euclidean distance from {@code from} to the target's nearest boundary cell
   * @param state the current traversal state
   * @param trailAverage the per-block cost of the trail leading to {@code from}
   * @return a cost estimate in seconds (not necessarily a lower bound)
   */
  double estimate(Cell from, double distance, TraversalState state, double trailAverage);
}
