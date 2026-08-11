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

  /**
   * Returns the block at the given local coordinates.
   *
   * @param localX 0–15 (block X within the chunk)
   * @param y the world Y
   * @param localZ 0–15 (block Z within the chunk)
   * @return the block
   */
  MinecraftBlock block(int localX, int y, int localZ);

  enum Unknown implements MinecraftChunk {
    INSTANCE;

    @Override
    public MinecraftBlock block(int localX, int y, int localZ) {
      return UnknownBlock.INSTANCE;
    }
  }
}
