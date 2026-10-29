/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import org.cobblestonemc.FutureOr;

/**
 * Where a search reads its terrain from: the terrain each section is classified from.
 *
 * <p>How terrain is read is the platform's business. A source may answer many sections with the
 * same object, as a whole chunk answers each of the sections stacked in it, and should read it once
 * for all of them: a search asks for a section and its neighbors above and below together.
 *
 * <p>⚠️ <b>An implementation must remember what it fetched.</b> Once a returned future has
 * completed, a later call for the same section has to answer immediately, or the search parks on
 * the same section forever.
 *
 * @param <C> the platform's terrain
 */
public interface TerrainSource<C> {

  /**
   * Returns the terrain a section is read from, or an immediate {@code null} if it cannot be known.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @return the terrain
   */
  FutureOr<C> section(int sectionX, int sectionY, int sectionZ);
}
