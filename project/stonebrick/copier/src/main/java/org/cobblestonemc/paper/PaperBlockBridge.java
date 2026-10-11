/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.block.data.BlockData;
import org.cobblestonemc.minecraft.MinecraftBlock;

/**
 * Hands the capture tool Paper's <em>real</em> block traits.
 *
 * <p><b>This class is in {@code org.cobblestonemc.paper} on purpose.</b> {@link PaperBlock} and
 * {@link PaperBlocks} are package-private, and the whole value of a captured trait table is that a
 * benchmark's blocks answer {@code isPassable()}, {@code breakTimeSeconds()} and {@code
 * supportsBoat()} exactly as a live server's do. Writing a second trait table in the copier would
 * work until the day someone fixed a trait in {@code PaperBlock} and not in the copy — at which
 * point every benchmark number would quietly stop predicting anything, and nothing would fail.
 *
 * <p>Declaring one class in another module's package gets that without widening {@code paper-core}
 * by a single public member. Package-private access works across jars here (there is no JPMS, and
 * the copier shades {@code paper-core} into its own jar), no production code changes, and the split
 * package lives entirely inside a module that is never shipped. If {@code PaperBlock} is renamed or
 * reshaped, this fails to compile — which is exactly the loud failure that makes the arrangement
 * safe to rely on.
 */
public final class PaperBlockBridge {

  private PaperBlockBridge() {}

  /**
   * Returns the block Cobblestone's search would see for the given block data.
   *
   * @param data the block data
   * @return the block
   */
  public static MinecraftBlock of(BlockData data) {
    return new PaperBlock(data);
  }
}
