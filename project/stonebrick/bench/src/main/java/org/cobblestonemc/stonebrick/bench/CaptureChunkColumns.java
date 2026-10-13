/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.ChunkFetch;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.lod.RegionGraph;
import org.cobblestonemc.stonebrick.platform.MissingCaptureLog;
import org.cobblestonemc.stonebrick.platform.StonebrickPlatformApi;

/**
 * A capture's chunk columns for the region layer, read through the simulated disk.
 *
 * <p>{@link CaptureProfiles} without the profiling: the same reads, charged to the same clock, so
 * the two coarse layers are compared on what they cost to feed as well as on what they estimate.
 */
final class CaptureChunkColumns implements RegionGraph.ChunkColumns {

  private final StonebrickPlatformApi platform;
  private final String world;
  private final int minY;
  private final int maxY;
  private final Map<Long, MinecraftChunk> chunks = new HashMap<>();
  private final Map<Long, CompletableFuture<MinecraftChunk>> inFlight = new HashMap<>();

  /** Reads past the capture's edge, kept apart from the capture's own log; see CaptureProfiles. */
  private final MissingCaptureLog beyond = new MissingCaptureLog();

  CaptureChunkColumns(StonebrickPlatformApi platform, String world) {
    this.platform = platform;
    this.world = world;
    MinecraftWorld minecraft = platform.world(world);
    this.minY = minecraft.minY();
    this.maxY = minecraft.maxY();
  }

  /** How many chunk columns were read, for the sweep's report. */
  int chunkColumns() {
    return chunks.size();
  }

  @Override
  public FutureOr<MinecraftChunk> column(int chunkX, int chunkZ) {
    long key = ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    if (chunks.containsKey(key)) {
      return FutureOr.of(chunks.get(key));
    }
    CompletableFuture<MinecraftChunk> reading = inFlight.get(key);
    if (reading == null) {
      reading =
          platform
              .fetchForProfile(world, chunkX, chunkZ, beyond)
              .thenApply(
                  fetch -> fetch instanceof ChunkFetch.Success success ? success.chunk() : null)
              .whenComplete(
                  (chunk, throwable) -> {
                    chunks.put(key, chunk);
                    inFlight.remove(key);
                  });
      if (!reading.isDone()) {
        inFlight.put(key, reading);
      }
    }
    // from(): under a zero-IO profile the read completes on the spot.
    return FutureOr.from(reading);
  }

  @Override
  public int minY() {
    return minY;
  }

  @Override
  public int maxY() {
    return maxY;
  }
}
