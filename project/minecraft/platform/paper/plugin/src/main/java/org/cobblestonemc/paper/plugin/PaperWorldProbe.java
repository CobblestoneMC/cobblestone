/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;

/**
 * Reads one world for one tick. Whether a chunk is readable is checked once per run of lookups in
 * it, not per block, and a block is read without creating a {@code Block}.
 */
final class PaperWorldProbe implements TrailSmoother.BlockProbe {

  private final World world;
  private final int minY;
  private final int maxY;
  private long lastChunk = Long.MIN_VALUE; // a chunk far beyond the world border
  private boolean lastReadable;

  PaperWorldProbe(World world) {
    this.world = world;
    this.minY = world.getMinHeight();
    this.maxY = world.getMaxHeight();
  }

  @Override
  public TrailBlock at(int x, int y, int z) {
    if (y < minY || y >= maxY) {
      return TrailBlock.OPEN;
    }
    int chunkX = x >> 4;
    int chunkZ = z >> 4;
    long chunk = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    if (chunk != lastChunk) {
      lastChunk = chunk;
      // Check ownership first (Folia), and never let a lookup load the chunk.
      lastReadable =
          Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)
              && world.isChunkLoaded(chunkX, chunkZ);
    }
    if (!lastReadable) {
      return TrailBlock.UNLOADED;
    }
    return world.getType(x, y, z).isSolid() ? TrailBlock.SOLID : TrailBlock.OPEN;
  }
}
