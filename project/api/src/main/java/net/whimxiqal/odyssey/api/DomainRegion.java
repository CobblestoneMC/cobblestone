/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

/**
 * A region of cells confined to a single {@link Domain} — the unifying "target/entry area"
 * abstraction. A single block, a 2×3 nether-portal plane, and a whole town are all
 * {@code DomainRegion}s.
 *
 * <p>It exposes geometry only; the cost estimate to reach it lives in the pluggable A* heuristic,
 * which picks its own metric over {@link #nearestBoundaryCell(Cell)}.
 *
 * @param <D> the domain type
 */
public interface DomainRegion<D extends Domain> {

  /**
   * Returns the domain this region lives in.
   *
   * @return the domain
   */
  D domain();

  /**
   * Returns whether the given cell is inside this region.
   *
   * @param cell the cell to test
   * @return {@code true} if contained
   */
  boolean contains(Cell cell);

  /**
   * Returns the cell of this region closest to {@code from} (the nearest entry point for prismatic
   * regions), or {@code from} itself if it is already inside.
   *
   * @param from the origin cell
   * @return the nearest boundary cell of this region
   */
  Cell nearestBoundaryCell(Cell from);
}
