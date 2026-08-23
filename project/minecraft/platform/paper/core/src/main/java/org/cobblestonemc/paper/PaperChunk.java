/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.ChunkSnapshot;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;

/**
 * A {@link MinecraftChunk} backed by a Bukkit {@link ChunkSnapshot} (immutable, thread-safe to
 * read).
 */
record PaperChunk(ChunkSnapshot snapshot) implements MinecraftChunk {

  @Override
  public MinecraftBlock block(int localX, int y, int localZ) {
    return new PaperBlock(snapshot.getBlockData(localX, y, localZ));
  }
}
