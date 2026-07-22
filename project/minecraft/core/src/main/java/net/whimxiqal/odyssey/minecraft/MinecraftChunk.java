/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

/**
 * An immutable 16×16×height snapshot of a chunk, safe to read from any thread. Wraps whatever
 * snapshot type the platform provides.
 */
public interface MinecraftChunk {

  /** the chunk's X coordinate (block X &gt;&gt; 4). */
  int chunkX();

  /** the chunk's Z coordinate (block Z &gt;&gt; 4). */
  int chunkZ();

  /** the inclusive minimum Y of the world. */
  int minY();

  /** the inclusive maximum Y of the world. */
  int maxY();

  /**
   * Returns the block at the given local coordinates.
   *
   * @param localX 0–15 (block X within the chunk)
   * @param y the world Y (between {@link #minY()} and {@link #maxY()})
   * @param localZ 0–15 (block Z within the chunk)
   * @return the block
   */
  MinecraftBlock block(int localX, int y, int localZ);
}
