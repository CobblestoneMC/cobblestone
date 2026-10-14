/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import org.cobblestonemc.DomainRegion;

/**
 * A region a search navigates to, made once that search exists. Most regions are pure geometry and
 * ignore the search ({@link #of}); one whose extent depends on the blocks, such as {@link
 * SurfaceRegion}, reads them through the search's own {@link ChunkSource}, sharing the chunks the
 * search fetches rather than fetching them a second time.
 */
@FunctionalInterface
public interface RegionFactory {

  /**
   * Makes the region for one search.
   *
   * @param chunks the search's chunk source
   * @return the region
   */
  DomainRegion<MinecraftWorld> create(ChunkSource chunks);

  /**
   * A region that reads no blocks, the same for every search.
   *
   * @param region the region
   * @return a factory that always makes {@code region}
   */
  static RegionFactory of(DomainRegion<MinecraftWorld> region) {
    return chunks -> region;
  }
}
