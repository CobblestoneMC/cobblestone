/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.MinecraftChunk;
import org.bukkit.ChunkSnapshot;

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
