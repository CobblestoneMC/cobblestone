/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.util.HashMap;
import java.util.Map;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.regions.TerrainSource;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.platform.Capture;
import org.cobblestonemc.stonebrick.platform.CaptureChunks;

/**
 * A capture's chunk columns read directly, without the simulated disk.
 *
 * <p>For the diagnostics rather than the benchmark. A report on estimate quality is not measuring
 * what the terrain costs to fetch, and routing it through the IO model would charge reads to a
 * solve nobody is timing.
 */
final class DirectCaptureChunks implements TerrainSource<MinecraftChunk> {

  private final Capture capture;
  private final String world;
  private final Map<Long, MinecraftChunk> chunks = new HashMap<>();

  DirectCaptureChunks(Capture capture, String world) {
    this.capture = capture;
    this.world = world;
  }

  @Override
  // Always immediate: the capture is already in memory.
  public FutureOr<MinecraftChunk> section(int chunkX, int sectionY, int chunkZ) {
    long key = ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    if (chunks.containsKey(key)) {
      return FutureOr.of(chunks.get(key));
    }
    MinecraftChunk chunk = null;
    try {
      ChunkColumn column = capture.column(world, chunkX, chunkZ);
      if (column != null) {
        chunk = CaptureChunks.chunk(capture, column, world);
      }
    } catch (CaptureFormatException e) {
      chunk = null;
    }
    chunks.put(key, chunk);
    return FutureOr.of(chunk);
  }
}
