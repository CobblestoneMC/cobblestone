/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.ChunkColumnCodec;
import org.cobblestonemc.stonebrick.format.TraitTable;

/**
 * Answers what a capture would cost before it is taken.
 *
 * <p><b>Not a nicety.</b> A capture that turns out to be 30 000 files and several gigabytes is
 * discovered too late once it is already on disk, and the region it covers is the one thing that
 * cannot be adjusted afterwards without re-running the whole job. Knowing the cost while still
 * standing in the world is what makes the vertical mode a decision rather than a guess.
 *
 * <p>Samples a scattered handful of columns and encodes them for real, then extrapolates.
 */
final class CaptureEstimate {

  /** How many columns to sample. Enough to average over terrain variety, few enough to be quick. */
  private static final int SAMPLES = 32;

  private CaptureEstimate() {}

  /**
   * Estimates a capture and reports it line by line.
   *
   * @param plugin the plugin, for scheduling
   * @param world the world
   * @param capture the target capture directory
   * @param mode the vertical mode
   * @param minChunkX rectangle bound
   * @param minChunkZ rectangle bound
   * @param maxChunkX rectangle bound
   * @param maxChunkZ rectangle bound
   * @param report receives the report lines
   */
  static void run(
      Plugin plugin,
      World world,
      CaptureDirectory capture,
      VerticalMode mode,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ,
      Consumer<String> report) {
    int width = maxChunkX - minChunkX + 1;
    int depth = maxChunkZ - minChunkZ + 1;
    int total = width * depth;
    report.accept(
        "Sampling %d of %d columns in %s…"
            .formatted(Math.min(SAMPLES, total), total, world.getKey()));

    List<CompletableFuture<byte[]>> samples = new ArrayList<>();
    for (int i = 0; i < Math.min(SAMPLES, total); i++) {
      // Spread the samples evenly rather than randomly: an estimate that changes between two runs
      // over the same region invites re-rolling it until it says something comfortable.
      int index = (int) ((long) i * total / Math.min(SAMPLES, total));
      int chunkX = minChunkX + (index % width);
      int chunkZ = minChunkZ + (index / width);
      samples.add(sample(plugin, world, mode, chunkX, chunkZ));
    }

    CompletableFuture.allOf(samples.toArray(CompletableFuture[]::new))
        .whenComplete(
            (ignored, error) ->
                Bukkit.getScheduler()
                    .runTaskAsynchronously(
                        plugin, () -> summarize(capture, mode, samples, total, world, report)));
  }

  private static CompletableFuture<byte[]> sample(
      Plugin plugin, World world, VerticalMode mode, int chunkX, int chunkZ) {
    return world
        .getChunkAtAsync(chunkX, chunkZ, false)
        .thenApply(
            chunk -> {
              if (chunk == null) {
                return new byte[0];
              }
              var snapshot = // includeMaxBlockY, because a surface-following capture asks the
                  // snapshot for its terrain
                  // height; without it getHighestBlockYAt has nothing to answer from. Biomes
                  // stay out:
                  // nothing in the capture format records them.
                  chunk.getChunkSnapshot(true, false, false);
              try {
                return ChunkColumnCodec.write(
                    ColumnCapture.capture(
                        snapshot,
                        world.getMinHeight(),
                        world.getMaxHeight() - 1,
                        mode,
                        TraitTable.builder()));
              } catch (IOException e) {
                return new byte[0];
              }
            })
        .exceptionally(error -> new byte[0]);
  }

  private static void summarize(
      CaptureDirectory capture,
      VerticalMode mode,
      List<CompletableFuture<byte[]>> samples,
      int total,
      World world,
      Consumer<String> report) {
    long rawBytes = 0;
    int counted = 0;
    for (CompletableFuture<byte[]> sample : samples) {
      byte[] bytes = sample.getNow(new byte[0]);
      if (bytes.length == 0) {
        continue;
      }
      rawBytes += bytes.length;
      counted++;
    }

    if (counted == 0) {
      report.accept("No chunks in that region are generated, so there is nothing to capture.");
      return;
    }

    long rawTotal = rawBytes / counted * total;

    report.accept(
        "%s  %,d columns  %s".formatted(world.getKey(), total, mode.toCommandArguments()));
    report.accept(
        "  on disk   %s   (sampled %d of %,d columns)".formatted(bytes(rawTotal), counted, total));

    int existing;
    try {
      existing = capture.countColumns();
    } catch (IOException e) {
      existing = 0;
    }
    report.accept(
        "  files     %,d   (capture total after this: %,d)".formatted(total, existing + total));
  }

  private static String bytes(long value) {
    if (value < 1024) {
      return value + " B";
    }
    if (value < 1024 * 1024) {
      return "%.1f KB".formatted(value / 1024.0);
    }
    return "%.1f MB".formatted(value / (1024.0 * 1024.0));
  }
}
