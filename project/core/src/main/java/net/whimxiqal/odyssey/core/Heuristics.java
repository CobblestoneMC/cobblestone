/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

/**
 * Factory for the built-in {@link HeuristicStrategy} implementations.
 *
 * <p>The {@code RunningAverageHeuristic} described in the design (which needs per-search context)
 * is deferred; the two provided here are stateless and consistent.
 */
public final class Heuristics {

  private Heuristics() {
  }

  /**
   * A trivially-admissible heuristic that always returns {@code 0}, turning Tier-2 A* into
   * uniform-cost (Dijkstra) search — always optimal for the explored terrain, but uninformed.
   *
   * @return the zero heuristic
   */
  public static HeuristicStrategy zero() {
    return (from, target, state) -> 0.0;
  }

  /**
   * An admissible, consistent heuristic: euclidean distance to the region's nearest boundary cell
   * times a globally-cheapest per-block cost.
   *
   * <p>{@code cheapestCostPerBlock} must be a true lower bound on the cost of moving one block by
   * any available means (e.g. the fastest mode's per-block cost); using a global lower bound keeps
   * the estimate admissible for every agent without needing per-agent knowledge here.
   *
   * @param cheapestCostPerBlock a lower bound on per-block traversal cost, in seconds
   * @return the euclidean heuristic
   */
  public static HeuristicStrategy euclidean(double cheapestCostPerBlock) {
    if (cheapestCostPerBlock < 0) {
      throw new IllegalArgumentException("cheapestCostPerBlock must be >= 0: " + cheapestCostPerBlock);
    }
    return (from, target, state) ->
        from.distance(target.nearestBoundaryCell(from)) * cheapestCostPerBlock;
  }
}
