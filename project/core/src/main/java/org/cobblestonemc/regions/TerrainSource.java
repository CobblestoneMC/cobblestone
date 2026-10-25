/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import org.cobblestonemc.FutureOr;

/**
 * Where a search reads its terrain from: one stack at a time, as wide as the terrain's {@link
 * RegionTerrain#sectionShift()} says.
 *
 * <p>⚠️ <b>An implementation must remember what it fetched.</b> Once a returned future has
 * completed, a later call for the same stack has to answer immediately, or the search parks on the
 * same stack forever.
 *
 * @param <C> the platform's stack of terrain
 */
public interface TerrainSource<C> {

  /**
   * Returns a stack of terrain, or an immediate {@code null} if it cannot be known.
   *
   * @param stackX the stack X
   * @param stackZ the stack Z
   * @return the stack
   */
  FutureOr<C> stack(int stackX, int stackZ);
}
