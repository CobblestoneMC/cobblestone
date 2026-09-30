/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeMap;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.lod.Medium;
import org.cobblestonemc.minecraft.lod.SectionProfile;
import org.cobblestonemc.minecraft.lod.SectionProfiler;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.cobblestonemc.stonebrick.platform.Capture;
import org.cobblestonemc.stonebrick.platform.CaptureChunks;

/**
 * Profiles every captured section and reports what the summaries look like.
 *
 * <p>The measurement phase 10 of the plan asks for, before any of the pyramid is built: how long a
 * column takes to profile, how many components a real section has, and how much of each medium the
 * terrain actually offers. Synthetic shapes cannot answer any of those — they say what the profiler
 * computes, not whether what it computes describes Minecraft.
 */
public final class ProfileReport {

  private ProfileReport() {}

  /**
   * Profiles a whole capture and prints a summary.
   *
   * @param corpusRoot the corpus root
   * @param captureName the capture to profile
   * @throws IOException if the capture cannot be read
   */
  public static void run(Path corpusRoot, String captureName) throws IOException {
    Capture capture = Capture.load(CorpusLayout.at(corpusRoot).capture(captureName).root());
    SectionProfiler profiler = new SectionProfiler();

    long sections = 0;
    long columns = 0;
    long components = 0;
    long merged = 0;
    long solid = 0;
    long nanos = 0;
    int maxComponents = 0;
    TreeMap<String, double[]> coverageByMedium = new TreeMap<>();
    for (Medium medium : Medium.ALL) {
      coverageByMedium.put(medium.name(), new double[2]);
    }

    for (String world : capture.worldKeys()) {
      for (int[] coordinates : CaptureChunks.columns(capture, world)) {
        ChunkColumn column;
        try {
          column = capture.column(world, coordinates[0], coordinates[1]);
        } catch (CaptureFormatException e) {
          throw new IOException(e);
        }
        if (column == null) {
          continue;
        }
        MinecraftChunk chunk = CaptureChunks.chunk(capture, column, world);
        columns++;
        for (int layer = 0; layer < Long.SIZE; layer++) {
          if ((column.sectionMask() & (1L << layer)) == 0) {
            continue;
          }
          int sectionY = column.minSectionY() + layer;
          long startedAt = System.nanoTime();
          SectionProfile profile = profiler.profile(chunk, sectionY);
          nanos += System.nanoTime() - startedAt;

          sections++;
          components += profile.components().size();
          maxComponents = Math.max(maxComponents, profile.components().size());
          if (profile.merged()) {
            merged++;
          }
          if (profile.solid()) {
            solid++;
          }
          SectionProfile.Component largest = profile.components().get(0);
          for (Medium medium : Medium.ALL) {
            double[] sum = coverageByMedium.get(medium.name());
            sum[0] += largest.coverage(SectionProfile.Axis.X, medium);
            sum[1]++;
          }
        }
      }
    }

    System.out.printf(Locale.ROOT, "capture %s%n", captureName);
    System.out.printf(Locale.ROOT, "  columns           %,d%n", columns);
    System.out.printf(Locale.ROOT, "  sections          %,d%n", sections);
    System.out.printf(
        Locale.ROOT,
        "  components        %,d total, %.2f mean, %d max%n",
        components,
        sections == 0 ? 0 : (double) components / sections,
        maxComponents);
    System.out.printf(
        Locale.ROOT,
        "  solid sections    %,d (%.0f%%)%n",
        solid,
        sections == 0 ? 0.0 : 100.0 * solid / sections);
    System.out.printf(Locale.ROOT, "  merged (capped)   %,d%n", merged);
    System.out.printf(
        Locale.ROOT,
        "  profiling cost    %.1f us/section, %.2f ms/column%n",
        sections == 0 ? 0.0 : nanos / 1000.0 / sections,
        columns == 0 ? 0.0 : nanos / 1e6 / columns);
    System.out.println("  mean X coverage of the largest component:");
    coverageByMedium.forEach(
        (name, sum) ->
            System.out.printf(
                Locale.ROOT, "    %-6s %.3f%n", name, sum[1] == 0 ? 0.0 : sum[0] / sum[1]));
  }
}
