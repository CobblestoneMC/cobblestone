/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

/**
 * A pluggable estimate of the remaining cost from a cell toward a target region — used both as the
 * Tier-1 optimistic edge cost and as the Tier-2 A* heuristic {@code h(n)}.
 *
 * <p>The estimate should be <b>optimistic</b> (a lower bound). If it is also consistent (the default
 * euclidean estimate is), Tier-2 A* returns least-cost paths for the terrain it explored. It is
 * intentionally domain-type-agnostic (takes a {@code DomainRegion<?>}), since it only needs the
 * region's geometry via {@link DomainRegion#nearestBoundaryCell(Cell)}.
 */
@FunctionalInterface
public interface HeuristicStrategy {

  /**
   * Estimates the remaining cost to get from {@code from} into {@code target} while in
   * {@code state}.
   *
   * @param from the current cell
   * @param target the region being sought
   * @param state the current traversal state
   * @return an optimistic (lower-bound) cost estimate in seconds
   */
  double estimate(Cell from, DomainRegion<?> target, TraversalState state);
}
