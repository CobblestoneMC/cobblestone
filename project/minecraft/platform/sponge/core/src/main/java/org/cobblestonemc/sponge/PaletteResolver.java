/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.io.IOException;
import java.util.Map;
import org.cobblestonemc.minecraft.MinecraftBlock;

/**
 * Turns one saved palette entry into a block.
 *
 * <p>Separated from {@link AnvilChunk} so that decoding a chunk — the region layout, the NBT, the
 * bit-packed indices — can be exercised without a running Sponge game behind it. In production the
 * only implementation is {@link SpongeBlocks}, which resolves entries through Sponge's own block
 * registry.
 */
interface PaletteResolver {

  /**
   * Returns the block a palette entry describes.
   *
   * @param entry the palette entry, with {@code Name} and optional {@code Properties}
   * @return the block
   * @throws IOException if the entry names no block, or names one this server does not have
   */
  MinecraftBlock resolve(Map<String, Object> entry) throws IOException;

  /**
   * Returns the block a section with no recorded blocks is made of.
   *
   * <p>Air, always — but it comes from here rather than from a constant so that deciding what a
   * block <em>is</em> stays in one place, and so that decoding a chunk never has to reach for
   * Sponge's registry on a path where no palette entry did.
   *
   * @return the air block
   */
  MinecraftBlock air();
}
