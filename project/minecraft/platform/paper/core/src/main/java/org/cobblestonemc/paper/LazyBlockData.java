/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.function.Supplier;
import org.bukkit.block.data.BlockData;
import org.cobblestonemc.Cell;

/**
 * The block data a chunk holds at one cell, read on first use and remembered.
 *
 * <p>This is what a break checker is handed. Checkers are promised the block's real state, and the
 * {@link org.cobblestonemc.minecraft.MinecraftBlock} a mode read cannot supply it: {@link
 * PaperBlocks} shares one instance per material, carrying that material's default state. So the
 * state is read from the chunk instead — but lazily, because reading it is a copy ({@link
 * PaperChunk#blockData}), a mining route asks about every block it considers breaking, and most
 * checkers decide on location alone and never look.
 *
 * <p>Confined to one composed verdict, hence no synchronization.
 */
final class LazyBlockData implements Supplier<BlockData> {

  private final PaperChunk chunk;
  private final Cell cell;
  private BlockData data;

  LazyBlockData(PaperChunk chunk, Cell cell) {
    this.chunk = chunk;
    this.cell = cell;
  }

  @Override
  public BlockData get() {
    if (data == null) {
      data = chunk.blockData(cell.x() & 15, cell.y(), cell.z() & 15);
    }
    return data;
  }
}
