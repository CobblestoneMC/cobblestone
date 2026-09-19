/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;

/**
 * A chunk on Paper, whatever it was read from, described in Bukkit's terms.
 *
 * <p>Paper gets a chunk two ways — a {@link LoadedPaperChunk snapshot} of one in memory, or an
 * {@link NMSChunk} decoded straight off disk — and everything above this class should not care
 * which. Both can say what {@link Material} and {@link BlockData} sit at a position, and that is
 * all anything needs: {@link PaperBlocks} turns them into the {@link MinecraftBlock} a search
 * reads, and a break checker is handed the block data itself (see {@link LazyBlockData}).
 *
 * <p>Implementations must be immutable and safe to read from any thread.
 */
abstract class PaperChunk implements MinecraftChunk {

  /**
   * Returns the material at the given local coordinates.
   *
   * <p>The cheap question, and the one asked tens of millions of times per search: implementations
   * must answer it without allocating.
   *
   * @param localX 0–15
   * @param y the world Y
   * @param localZ 0–15
   * @return the material
   */
  abstract Material blockType(int localX, int y, int localZ);

  /**
   * Returns the full block state at the given local coordinates.
   *
   * <p>The expensive question: Bukkit block data is mutable, so every implementation hands back a
   * fresh copy. Ask it only when the material is not enough.
   *
   * @param localX 0–15
   * @param y the world Y
   * @param localZ 0–15
   * @return a fresh copy of the block data
   */
  abstract BlockData blockData(int localX, int y, int localZ);

  @Override
  public MinecraftBlock block(int localX, int y, int localZ) {
    return PaperBlocks.of(this, localX, y, localZ);
  }
}
