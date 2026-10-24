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
import org.cobblestonemc.regions.RegionColumn;
import org.cobblestonemc.regions.TerrainSource;
import org.cobblestonemc.stonebrick.platform.MissingCaptureLog;
import org.cobblestonemc.stonebrick.platform.StonebrickPlatformApi;

/**
 * A capture's chunk columns for the region layer, read through the simulated disk.
 *
 * <p>Reads go through the same simulated disk as the search's own chunk fetches. Under a {@code
 * zero} profile every answer is immediate; under {@code spinning} or {@code nvme} a column not yet
 * resident comes back pending and the fine search parks, which is the path a live server is in
 * constantly.
 */
final class CaptureChunkColumns implements TerrainSource<MinecraftChunk> {

  private final StonebrickPlatformApi platform;
  private final String world;
  private final Map<Long, MinecraftChunk> chunks = new HashMap<>();
  private final Map<Long, CompletableFuture<MinecraftChunk>> inFlight = new HashMap<>();

  /**
   * Reads past the capture's edge, kept apart from the capture's own log. The backward search is
   * expected to ask about terrain the fine search never touches: that is the edge of what is known,
   * not a degenerate run.
   */
  private final MissingCaptureLog beyond = new MissingCaptureLog();

  CaptureChunkColumns(StonebrickPlatformApi platform, String world) {
    this.platform = platform;
    this.world = world;
  }

  /** How many chunk columns were read, for the sweep's report. */
  int chunkColumns() {
    return chunks.size();
  }

  /** Time spent reading and decoding columns, where the read completed on the spot. */
  private long fetchNanos;

  long fetchNanos() {
    return fetchNanos;
  }

  @Override
  public FutureOr<MinecraftChunk> column(int chunkX, int chunkZ) {
    long key = RegionColumn.key(chunkX, chunkZ);
    if (chunks.containsKey(key)) {
      return FutureOr.of(chunks.get(key));
    }
    CompletableFuture<MinecraftChunk> reading = inFlight.get(key);
    if (reading == null) {
      // Under a zero-IO profile the read and its decode run right here, so this is their cost.
      long started = System.nanoTime();
      reading =
          platform
              .fetchForRegions(world, chunkX, chunkZ, beyond)
              .thenApply(
                  fetch -> fetch instanceof ChunkFetch.Success success ? success.chunk() : null)
              .whenComplete(
                  (chunk, throwable) -> {
                    chunks.put(key, chunk);
                    inFlight.remove(key);
                  });
      fetchNanos += System.nanoTime() - started;
      if (!reading.isDone()) {
        inFlight.put(key, reading);
      }
    }
    // from(): under a zero-IO profile the read completes on the spot.
    return FutureOr.from(reading);
  }
}
