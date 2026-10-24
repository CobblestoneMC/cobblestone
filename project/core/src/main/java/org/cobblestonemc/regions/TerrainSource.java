/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import org.cobblestonemc.FutureOr;

/**
 * Where a search reads its terrain from: one column at a time, as wide as the terrain's {@link
 * RegionTerrain#columnShift()} says.
 *
 * <p>⚠️ <b>An implementation must remember what it fetched.</b> Once a returned future has
 * completed, a later call for the same column has to answer immediately, or the search parks on the
 * same column forever.
 *
 * @param <C> the platform's column of terrain
 */
public interface TerrainSource<C> {

  /**
   * Returns a column of terrain, or an immediate {@code null} if it cannot be known.
   *
   * @param columnX the column X
   * @param columnZ the column Z
   * @return the column
   */
  FutureOr<C> column(int columnX, int columnZ);
}
