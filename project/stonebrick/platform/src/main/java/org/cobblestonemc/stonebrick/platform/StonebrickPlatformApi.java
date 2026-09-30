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
  private final java.util.Set<Long> ungenerated = new java.util.HashSet<>();
  private final MissingCaptureLog missing;
  private final Map<String, StonebrickWorld> worlds = new HashMap<>();

  /** Reads made to fill the profile layer rather than to expand the search. */
  private final java.util.concurrent.atomic.AtomicLong profileReads =
      new java.util.concurrent.atomic.AtomicLong();

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
    this.missing = capture.missing();
    for (String key : capture.worldKeys()) {
      worlds.put(
          key,
          new StonebrickWorld(
              this, settings, key, capture.minY(key), capture.maxY(key), capture.environment(key)));
    }
  }

  /**
   * Declares chunks to present as never generated, whatever the capture holds.
   *
   * <p>A live server meets terrain that does not exist — at a world border, or under a policy that
   * will not generate it — and a corpus captured from a seeded world would otherwise never exercise
   * that. Declaring it keeps the captured terrain complete and the case tested.
   *
   * <p>These are <b>not</b> recorded as missing capture data: nothing is missing, the scenario
   * asked for them to be absent.
   *
   * @param chunks packed {@code (x &lt;&lt; 32) | z} chunk coordinates
   */
  public void presentAsUngenerated(java.util.Set<Long> chunks) {
    ungenerated.addAll(chunks);
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
    return read(world.key(), chunkX, chunkZ, missing, false);
  }

  /**
   * Fetches a column for the profile layer.
   *
   * <p><b>The same disk, deliberately.</b> Charging profile reads against the same {@link
   * SimulatedChunkIo} puts them in the same queue as the search's own, which is what a live server
   * does: the coarse pass and the fine search contend for one set of channels, and their latency
   * lands on the same virtual clock. Giving profiling its own free disk would make the layer look
   * cheaper than it is, which is the thing worth measuring.
   *
   * <p>Its <em>missing</em>-capture accounting is separate, though, because reaching past the
   * capture while profiling is expected -- the coarse search spreads outward -- and folding that
   * into the search's log would have every coarse run declare itself degenerate.
   *
   * @param worldKey the world
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param log where to record reads outside the capture
   * @return the column, or a future of {@code null} where there is none
   */
  public CompletableFuture<ChunkFetch> fetchForProfile(
      String worldKey, int chunkX, int chunkZ, MissingCaptureLog log) {
    return read(worldKey, chunkX, chunkZ, log, true);
  }

  private CompletableFuture<ChunkFetch> read(
      String key, int chunkX, int chunkZ, MissingCaptureLog log, boolean forProfile) {
    if (ungenerated.contains(((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL))) {
      return CompletableFuture.completedFuture(ChunkFetch.Failed.permanent());
    }
    int storedBytes = capture.storedBytes(key, chunkX, chunkZ);
    if (storedBytes < 0) {
      // Not captured. Indistinguishable from ungenerated terrain to everything above, which is
      // exactly why it is recorded here — and charged nothing, because a read that finds no file
      // is not a read.
      log.missingColumn(key, chunkX, chunkZ);
      return CompletableFuture.completedFuture(ChunkFetch.Failed.permanent());
    }
    if (forProfile) {
      // Counted here rather than on entry: a column that is ungenerated or uncaptured returns
      // above without touching the disk, and counting those would let the profile share exceed
      // the total reads it is supposed to be part of.
      profileReads.incrementAndGet();
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
              log.missingColumn(key, chunkX, chunkZ);
              return ChunkFetch.Failed.permanent();
            }
            return ChunkFetch.success(StonebrickChunk.of(column, capture.traits(), key, log));
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
   * Returns how many of the reads were made to fill the profile layer.
   *
   * <p>Reported apart from {@link #ioStats()} so the coarse layer's share of the disk is legible
   * rather than buried in the search's total.
   *
   * @return the profile read count
   */
  public long profileReads() {
    return profileReads.get();
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
