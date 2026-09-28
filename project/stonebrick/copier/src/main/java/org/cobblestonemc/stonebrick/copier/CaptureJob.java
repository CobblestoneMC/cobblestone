/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.ChunkColumnCodec;
import org.cobblestonemc.stonebrick.format.TraitTable;

/**
 * One run of {@code /copier copy}: walks a rectangle of chunks, writes a column file for each, and
 * writes the capture's trait table and metadata when it finishes.
 *
 * <p><b>Rate-limited on purpose.</b> A capture can ask for tens of thousands of chunks, and a
 * server that stalls for a minute while a developer takes a capture is a server nobody will let
 * anyone take captures on. A small per-tick budget with a bounded number of loads in flight keeps
 * the tick time flat and makes the job take longer instead — which is the right trade for tooling.
 *
 * <p>Only one job runs at a time per plugin instance. Two concurrent captures into the same
 * directory could interleave writes to the same trait table, and the second would win with a table
 * missing the first's states.
 */
final class CaptureJob {

  /** Chunks requested per tick. Small: each one costs a snapshot copy on the server thread. */
  private static final int CHUNKS_PER_TICK = 8;

  /** How many chunk loads may be outstanding before the pump waits. */
  private static final int MAX_IN_FLIGHT = 32;

  private final Plugin plugin;
  private final World world;
  private final CaptureDirectory capture;
  private final VerticalMode mode;
  private final boolean generate;
  private final int minChunkX;
  private final int minChunkZ;
  private final int maxChunkX;
  private final int maxChunkZ;
  private final int total;
  private final ChunkFilter filter;
  private final Consumer<String> progress;

  /**
   * Which chunks of the rectangle to actually capture.
   *
   * <p>A rectangle is what a person types, but it is not what a route needs. The terrain a search
   * reaches for is a capsule around its line, and the bounding box of a long diagonal capsule holds
   * several times as many chunks as the capsule does — all of which would be captured, written and
   * never read.
   */
  @FunctionalInterface
  interface ChunkFilter {
    /**
     * Returns whether a chunk should be captured.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return {@code true} to capture it
     */
    boolean accepts(int chunkX, int chunkZ);
  }

  private final TraitTable.Builder traits = TraitTable.builder();
  private final AtomicInteger requested = new AtomicInteger();
  private final AtomicInteger completed = new AtomicInteger();
  private final AtomicInteger written = new AtomicInteger();
  private final AtomicInteger unchanged = new AtomicInteger();
  private final AtomicInteger failed = new AtomicInteger();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean finished = new AtomicBoolean();

  private final long startedAt = System.currentTimeMillis();
  private long lastReportAt = startedAt;
  private BukkitTask pump;

  CaptureJob(
      Plugin plugin,
      World world,
      CaptureDirectory capture,
      VerticalMode mode,
      boolean generate,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ,
      ChunkFilter filter,
      Consumer<String> progress) {
    this.plugin = plugin;
    this.world = world;
    this.capture = capture;
    this.mode = mode;
    this.generate = generate;
    this.minChunkX = minChunkX;
    this.minChunkZ = minChunkZ;
    this.maxChunkX = maxChunkX;
    this.maxChunkZ = maxChunkZ;
    this.filter = filter;
    this.progress = progress;
    int counted = 0;
    for (int x = minChunkX; x <= maxChunkX; x++) {
      for (int z = minChunkZ; z <= maxChunkZ; z++) {
        if (filter.accepts(x, z)) {
          counted++;
        }
      }
    }
    this.total = counted;
  }

  /** Returns how many chunks this job will visit. */
  int total() {
    return total;
  }

  /** Returns a one-line status. */
  String status() {
    return "%s: %d/%d chunks (%d written, %d unchanged, %d failed), %ds elapsed"
        .formatted(
            world.getKey().asString(),
            completed.get(),
            total,
            written.get(),
            unchanged.get(),
            failed.get(),
            (System.currentTimeMillis() - startedAt) / 1000);
  }

  /** Returns whether the job has stopped, for any reason. */
  boolean isFinished() {
    return finished.get();
  }

  /** Asks the job to stop; already-issued chunk loads still complete and are written. */
  void cancel() {
    cancelled.set(true);
  }

  /** Starts the job. Must be called on the server thread. */
  void start() {
    progress.accept(
        "Capturing %d chunks of %s into %s (%s)"
            .formatted(
                total, world.getKey().asString(), capture.root(), mode.toCommandArguments()));
    pump = Bukkit.getScheduler().runTaskTimer(plugin, this::pump, 1L, 1L);
  }

  private void pump() {
    if (cancelled.get()) {
      finish();
      return;
    }
    for (int i = 0; i < CHUNKS_PER_TICK; i++) {
      if (inFlight.get() >= MAX_IN_FLIGHT) {
        break;
      }
      int index = requested.getAndIncrement();
      if (index >= total) {
        requested.set(total);
        break;
      }
      request(index);
    }
    report();
    if (completed.get() >= total) {
      finish();
    }
  }

  private void request(int index) {
    int[] at = nth(index);
    if (at == null) {
      completed.incrementAndGet();
      return;
    }
    int chunkX = at[0];
    int chunkZ = at[1];
    inFlight.incrementAndGet();
    // Generation is off unless asked for. On a seeded corpus world it is exactly what we want —
    // terrain is a deterministic function of the seed, so generating it is as reproducible as
    // reading it. On somebody's live server it would silently write new chunks into their save,
    // which is not a thing a capture tool should do by default.
    world
        .getChunkAtAsync(chunkX, chunkZ, generate)
        .whenComplete(
            (chunk, error) -> {
              try {
                if (error != null || chunk == null) {
                  failed.incrementAndGet();
                  return;
                }
                // Snapshot on whatever thread the future completed on — Paper completes chunk
                // futures on the thread that owns the chunk, which is where snapshotting is legal.
                var snapshot = // includeMaxBlockY, because a surface-following capture asks the
                    // snapshot for its terrain
                    // height; without it getHighestBlockYAt has nothing to answer from. Biomes
                    // stay out:
                    // nothing in the capture format records them.
                    chunk.getChunkSnapshot(true, false, false);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> encodeAndWrite(snapshot));
              } finally {
                inFlight.decrementAndGet();
              }
            });
  }

  /** The {@code index}-th accepted chunk, in row-major order, or {@code null} if there is none. */
  private int[] nth(int index) {
    int seen = 0;
    for (int z = minChunkZ; z <= maxChunkZ; z++) {
      for (int x = minChunkX; x <= maxChunkX; x++) {
        if (filter.accepts(x, z) && seen++ == index) {
          return new int[] {x, z};
        }
      }
    }
    return null;
  }

  private void encodeAndWrite(org.bukkit.ChunkSnapshot snapshot) {
    try {
      ChunkColumn column;
      synchronized (traits) {
        column =
            ColumnCapture.capture(
                snapshot, world.getMinHeight(), world.getMaxHeight() - 1, mode, traits);
      }
      boolean changed =
          ChunkColumnCodec.write(
              column, capture.column(world.getKey().asString(), snapshot.getX(), snapshot.getZ()));
      if (changed) {
        written.incrementAndGet();
      } else {
        unchanged.incrementAndGet();
      }
    } catch (IOException | RuntimeException e) {
      failed.incrementAndGet();
      plugin
          .getLogger()
          .warning(
              "Failed to capture chunk "
                  + snapshot.getX()
                  + ","
                  + snapshot.getZ()
                  + ": "
                  + e.getMessage());
    } finally {
      completed.incrementAndGet();
    }
  }

  private void report() {
    long now = System.currentTimeMillis();
    if (now - lastReportAt < 5_000) {
      return;
    }
    lastReportAt = now;
    int done = completed.get();
    long elapsed = now - startedAt;
    String eta = done == 0 ? "?" : ((elapsed * (total - done) / done) / 1000) + "s";
    progress.accept("  %d/%d chunks, ETA %s".formatted(done, total, eta));
  }

  private void finish() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    if (pump != null) {
      pump.cancel();
    }
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                TraitTable table;
                synchronized (traits) {
                  table = traits.build();
                }
                mergeAndWriteTraits(table);
                writeMeta();
                progress.accept(
                    (cancelled.get() ? "Capture cancelled. " : "Capture complete. ") + status());
                progress.accept(
                    "  %d block states described in %s"
                        .formatted(table.size(), capture.traitTable().getFileName()));
              } catch (IOException e) {
                progress.accept("Failed to finish the capture: " + e.getMessage());
              }
            });
  }

  /**
   * Writes the trait table, keeping states an earlier capture into this directory recorded.
   *
   * <p>A capture directory accumulates: a scenario's terrain may arrive in several runs, and a
   * later run that dropped the states an earlier one had seen would leave the directory's own
   * column files referencing traits nothing describes.
   */
  private void mergeAndWriteTraits(TraitTable table) throws IOException {
    TraitTable.Builder merged = TraitTable.builder();
    if (java.nio.file.Files.exists(capture.traitTable())) {
      TraitTable.read(capture.traitTable()).asMap().forEach(merged::add);
    }
    table.asMap().forEach(merged::add);
    merged.build().write(capture.traitTable());
  }

  private void writeMeta() throws IOException {
    Map<String, String> meta = new LinkedHashMap<>();
    try {
      meta.putAll(capture.readMeta());
    } catch (IOException ignored) {
      // No metadata yet, or it is unreadable; this capture rewrites it either way.
    }
    String key = world.getKey().asString();
    meta.put("world." + key + ".minY", String.valueOf(world.getMinHeight()));
    meta.put("world." + key + ".maxY", String.valueOf(world.getMaxHeight() - 1));
    meta.put("world." + key + ".environment", world.getEnvironment().name());
    meta.put("minecraftVersion", Bukkit.getMinecraftVersion());
    meta.put("copierVersion", plugin.getPluginMeta().getVersion());
    meta.put("capturedAt", Instant.ofEpochMilli(startedAt).toString());
    capture.writeMeta(meta);
  }
}
