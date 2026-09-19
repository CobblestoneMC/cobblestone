/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * A {@link PaperChunk} backed by a Bukkit {@link ChunkSnapshot} of a chunk that was in memory
 * (immutable, thread-safe to read).
 */
final class LoadedPaperChunk extends PaperChunk {

  private final ChunkSnapshot snapshot;

  LoadedPaperChunk(ChunkSnapshot snapshot) {
    this.snapshot = snapshot;
  }

  @Override
  Material blockType(int localX, int y, int localZ) {
    return snapshot.getBlockType(localX, y, localZ);
  }

  @Override
  BlockData blockData(int localX, int y, int localZ) {
    return snapshot.getBlockData(localX, y, localZ);
  }
}
