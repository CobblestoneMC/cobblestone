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
import org.cobblestonemc.minecraft.lod.CoarseSearch;
import org.cobblestonemc.minecraft.lod.SectionProfile;
import org.cobblestonemc.minecraft.lod.SectionProfiler;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.platform.Capture;
import org.cobblestonemc.stonebrick.platform.CaptureChunks;

/**
 * Profiles a capture's sections directly, without the simulated disk.
 *
 * <p>For the diagnostics rather than the benchmark. A report on estimate quality is not measuring
 * what the terrain costs to fetch, and routing it through the IO model would charge reads to a
 * solve nobody is timing.
 */
final class DirectProfiles implements CoarseSearch.SectionProfiles {

  private final Capture capture;
  private final String world;
  private final SectionProfiler profiler = new SectionProfiler();
  private final Map<Long, SectionProfile> cache = new HashMap<>();
  private final Map<Long, MinecraftChunk> chunks = new HashMap<>();

  DirectProfiles(Capture capture, String world) {
    this.capture = capture;
    this.world = world;
  }

  int profiled() {
    return cache.size();
  }

  @Override
  // Always immediate: this report reads a capture already in memory.
  public FutureOr<SectionProfile> at(int sx, int sy, int sz) {
    long key = CoarseSearch.key(sx, sy, sz);
    if (cache.containsKey(key)) {
      return FutureOr.of(cache.get(key));
    }
    MinecraftChunk chunk = chunkAt(sx, sz);
    SectionProfile profile = chunk == null ? null : profiler.profile(chunk, sy);
    cache.put(key, profile);
    return FutureOr.of(profile);
  }

  private MinecraftChunk chunkAt(int chunkX, int chunkZ) {
    long key = ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    if (chunks.containsKey(key)) {
      return chunks.get(key);
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
    return chunk;
  }
}
