/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.minecraft.ChunkFetch;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.PlatformApi;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.jetbrains.annotations.Nullable;

/**
 * The {@link PlatformApi} that reads a capture instead of a server.
 *
 * <p>Everything above this seam — the chunk provider, the world model, every mode, the whole search
 * — is the code that runs in production. That is the entire point: a benchmark is only worth
 * running if the thing it measures is the thing that ships.
 */
public final class StonebrickPlatformApi implements PlatformApi<Object> {

  private final Capture capture;
  private final MinecraftScheduler<Object> scheduler;
  private final ChunkProviderSettings settings;
  private final SimulatedChunkIo io;
  private final MissingCaptureLog missing = new MissingCaptureLog();
  private final Map<String, StonebrickWorld> worlds = new HashMap<>();

  /**
   * Creates a platform over a loaded capture, with no simulated IO cost.
   *
   * @param capture the capture
   * @param scheduler the scheduler
   * @param settings the chunk provider tunables each world will use
   */
  public StonebrickPlatformApi(
      Capture capture, MinecraftScheduler<Object> scheduler, ChunkProviderSettings settings) {
    this(capture, scheduler, settings, IoProfile.zero());
  }

  /**
   * Creates a platform over a loaded capture, charging the given cost for each chunk read.
   *
   * @param capture the capture
   * @param scheduler the scheduler
   * @param settings the chunk provider tunables each world will use
   * @param profile what a chunk read costs
   */
  public StonebrickPlatformApi(
      Capture capture,
      MinecraftScheduler<Object> scheduler,
      ChunkProviderSettings settings,
      IoProfile profile) {
    this.capture = capture;
    this.scheduler = scheduler;
    this.settings = settings;
    this.io = new SimulatedChunkIo(profile, scheduler.time());
    for (String key : capture.worldKeys()) {
      worlds.put(
          key,
          new StonebrickWorld(
              this, settings, key, capture.minY(key), capture.maxY(key), capture.environment(key)));
    }
  }

  /**
   * Returns the world with the given key, or {@code null} if the capture has none.
   *
   * @param key the namespaced world key
   * @return the world, or {@code null}
   */
  public @Nullable StonebrickWorld world(String key) {
    return worlds.get(key);
  }

  /**
   * Returns the log of reads that fell outside the capture.
   *
   * <p>A runner must check this when a solve finishes. A result produced alongside a non-empty log
   * is not a slow result or a failed one — it is a result about a world that does not exist, and
   * reporting it as a benchmark number is the worst thing this harness could do.
   *
   * @return the missing-capture log
   */
  public MissingCaptureLog missing() {
    return missing;
  }

  /**
   * Returns the capture being read.
   *
   * @return the capture
   */
  public Capture capture() {
    return capture;
  }

  @Override
  public MinecraftScheduler<Object> scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<ChunkFetch> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent) {
    // `urgent` must not change the answer — a platform that declined speculative work and did it
    // for a blocked caller would have the caller remember a refusal as a fact about the world.
    // Here it changes nothing at all, because there is no queue to prioritise yet; the simulated
    // IO model is what will give it meaning.
    String key = world.key();
    int storedBytes = capture.storedBytes(key, chunkX, chunkZ);
    if (storedBytes < 0) {
      // Not captured. Indistinguishable from ungenerated terrain to everything above, which is
      // exactly why it is recorded here — and charged nothing, because a read that finds no file
      // is not a read.
      missing.missingColumn(key, chunkX, chunkZ);
      return CompletableFuture.completedFuture(ChunkFetch.Failed.permanent());
    }
    // Decoding happens inside the read, after its modelled delay: a live server pays its decode as
    // part of the read, and charging it up front would let a prefetch nobody waits on cost the
    // solve real work.
    return io.read(
        chunkX,
        chunkZ,
        storedBytes,
        () -> {
          try {
            ChunkColumn column = capture.column(key, chunkX, chunkZ);
            if (column == null) {
              missing.missingColumn(key, chunkX, chunkZ);
              return ChunkFetch.Failed.permanent();
            }
            return ChunkFetch.success(StonebrickChunk.of(column, capture.traits(), key, missing));
          } catch (CaptureFormatException e) {
            // Stored bytes that will not decode are a corrupt corpus, not a transient read error.
            // Saying "transient" would have the provider retry a file that can never parse.
            throw new IllegalStateException(
                "capture "
                    + capture.name()
                    + " will not decode "
                    + key
                    + " "
                    + chunkX
                    + ","
                    + chunkZ,
                e);
          }
        });
  }

  /**
   * Returns what the simulated disk has done so far.
   *
   * @return a snapshot of the IO counters
   */
  public IoStats ioStats() {
    return new IoStats(io.reads(), io.coldReads(), io.totalDelayMicros());
  }

  /**
   * Returns the chunk provider tunables the worlds were built with.
   *
   * @return the settings
   */
  public ChunkProviderSettings settings() {
    return settings;
  }
}
