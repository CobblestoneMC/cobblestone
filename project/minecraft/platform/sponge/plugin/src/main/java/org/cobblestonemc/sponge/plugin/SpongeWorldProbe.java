/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.Map;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * Reads one world for one tick, checking whether a chunk is loaded once per run of lookups. Whether
 * a block state is solid is remembered in a cache the caller may keep across ticks.
 */
final class SpongeWorldProbe implements TrailSmoother.BlockProbe {

  private final ServerWorld world;
  private final Map<BlockState, Boolean> solidByState;
  private final int minY;
  private final int maxY;
  private long lastChunk = Long.MIN_VALUE; // a chunk far beyond the world border
  private boolean lastLoaded;

  SpongeWorldProbe(ServerWorld world, Map<BlockState, Boolean> solidByState) {
    this.world = world;
    this.solidByState = solidByState;
    this.minY = world.min().y();
    this.maxY = world.max().y();
  }

  @Override
  public TrailBlock at(int x, int y, int z) {
    if (y < minY || y > maxY) {
      return TrailBlock.OPEN;
    }
    long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
    if (chunk != lastChunk) {
      lastChunk = chunk;
      lastLoaded = world.isBlockLoaded(x, y, z); // never let a lookup load the chunk
    }
    if (!lastLoaded) {
      return TrailBlock.UNLOADED;
    }
    return solidByState.computeIfAbsent(
            world.block(x, y, z), state -> state.get(Keys.IS_SOLID).orElse(false))
        ? TrailBlock.SOLID
        : TrailBlock.OPEN;
  }
}
