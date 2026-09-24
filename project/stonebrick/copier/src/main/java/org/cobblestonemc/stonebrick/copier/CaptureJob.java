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
  private final int minChunkX;
  private final int minChunkZ;
  private final int maxChunkX;
  private final int maxChunkZ;
  private final int total;
  private final Consumer<String> progress;

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
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ,
      Consumer<String> progress) {
    this.plugin = plugin;
    this.world = world;
    this.capture = capture;
    this.mode = mode;
    this.minChunkX = minChunkX;
    this.minChunkZ = minChunkZ;
    this.maxChunkX = maxChunkX;
    this.maxChunkZ = maxChunkZ;
    this.total = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
    this.progress = progress;
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
    int width = maxChunkX - minChunkX + 1;
    int chunkX = minChunkX + (index % width);
    int chunkZ = minChunkZ + (index / width);
    inFlight.incrementAndGet();
    // `false`: never generate. A capture must record the world as it is, not conjure terrain that
    // no player has ever seen and that would differ on the next world-gen change.
    world
        .getChunkAtAsync(chunkX, chunkZ, false)
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
