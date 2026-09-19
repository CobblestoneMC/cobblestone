/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

/**
 * A region of block cells in a single world, in a platform's own types. A single block, a portal
 * plane and a whole town are all regions.
 *
 * @param <W> the world type
 * @param <V> the block-position type
 */
public interface WorldRegion<W, V> {

  /**
   * Returns the world this region lives in.
   *
   * @return the world
   */
  W world();

  /**
   * Returns whether the given block position is inside this region.
   *
   * @param location the block position to test
   * @return {@code true} if contained
   */
  boolean contains(V location);

  /**
   * Returns the block position of this region closest to {@code location}, or {@code location}
   * itself if it is already inside.
   *
   * @param location the position to measure from
   * @return the nearest boundary position of this region
   */
  V nearestBoundaryLocation(V location);
}
