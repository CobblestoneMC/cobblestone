/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.SearchObserver;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.lod.CoarseCost;
import org.cobblestonemc.minecraft.lod.CoarseSearch;
import org.cobblestonemc.minecraft.lod.Medium;
import org.cobblestonemc.minecraft.lod.SectionProfile;
import org.cobblestonemc.minecraft.lod.SectionProfiler;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.cobblestonemc.stonebrick.platform.Capture;
import org.cobblestonemc.stonebrick.platform.CaptureChunks;

/**
 * Measures how well the coarse estimate predicts what the fine search actually pays.
 *
 * <p><b>The go/no-go measurement for the whole coarse tier.</b> Everything above it — the pyramid,
 * the aggregation, the refinement — is machinery for producing this number more cheaply over longer
 * distances. If the estimate does not track realized cost at one level over one capture, none of
 * that machinery will rescue it, and it is far better to find that out in an afternoon than after
 * building four levels of it.
 *
 * <p><b>Measured along the path, not over the whole explored area.</b> The true remaining cost from
 * an arbitrary cell is not known without solving from it; along a found path it is exactly the path
 * cost minus what has been spent. So each cell of the solved route gives one honest (estimate,
 * truth) pair — and the route is where estimate quality decides what the search does.
 */
public final class HeuristicAccuracy {

  /** Component expansions one estimate may spend. Generous: this is a measurement, not a solve. */
  private static final long QUERY_BUDGET = 200_000;

  private HeuristicAccuracy() {}

  /**
   * Runs a scenario, then compares the coarse estimate against realized cost along its path.
   *
   * @param corpusRoot the corpus root
   * @param scenario the scenario
   * @param logger where the search logs
   * @throws IOException if the capture cannot be read
   */
  public static void run(
      Path corpusRoot, Scenario scenario, Loadout loadout, CobblestoneLogger logger)
      throws IOException {
    RunResult result =
        new ScenarioRunner(corpusRoot, logger).run(scenario, loadout, SearchObserver.none());
    if (!"success".equals(result.outcome())) {
      System.out.println(
          "scenario " + scenario.id() + " did not find a path (" + result.outcome() + ")");
      if (!result.isValid()) {
        System.out.println(result.missingCaptureReport());
      }
      return;
    }

    Capture capture = Capture.load(CorpusLayout.at(corpusRoot).capture(scenario.capture()).root());
    List<Cell> path = pathOf(corpusRoot, scenario, loadout, logger);
    if (path.isEmpty()) {
      System.out.println("no path cells recorded");
      return;
    }
    double total = result.pathCost();

    System.out.printf(
        Locale.ROOT, "h3 accuracy: %s  (%,d path cells)%n", scenario.id(), path.size());
    System.out.printf(
        Locale.ROOT,
        "%-10s %10s %10s %8s %8s %10s %8s%n",
        "fallback",
        "est@start",
        "real@start",
        "ratio",
        "corr",
        "mean est",
        "optim%");
    System.out.println("-".repeat(64));

    // The fallback rate is this layer's dominant knob, and the harness exists so it can be picked
    // from measurement rather than argued about. Swept over the plausible range: from "an uncovered
    // slice is no worse than walking" to "it is twice as bad as mining through".
    for (double fallback :
        new double[] {Medium.WALK.costPerBlock(), 0.4, 0.7, Medium.MINE.costPerBlock(), 2.8}) {
      Profiles profiles = new Profiles(capture, scenario.world());
      CoarseCost cost = CoarseCost.forMediums(mediumsFor(loadout), fallback);
      CoarseSearch coarse = new CoarseSearch(profiles, cost, scenario.destination());

      List<double[]> pairs = new ArrayList<>();
      double perStep = total / Math.max(1, path.size() - 1);
      double spent = 0;
      for (Cell cell : path) {
        pairs.add(new double[] {coarse.costToGoal(cell, QUERY_BUDGET), total - spent});
        spent += perStep;
      }
      reportRow(fallback, pairs);
    }
    System.out.println();
    System.out.println(
        "  Correlation is what decides the model; the ratio only decides a constant.");
  }

  private static void reportRow(double fallback, List<double[]> pairs) {
    double sumRatio = 0;
    int optimistic = 0;
    double meanEstimate = 0;
    double meanRealized = 0;
    for (double[] pair : pairs) {
      sumRatio += pair[0] / Math.max(1e-6, pair[1]);
      if (pair[0] <= pair[1]) {
        optimistic++;
      }
      meanEstimate += pair[0];
      meanRealized += pair[1];
    }
    int n = pairs.size();
    meanEstimate /= n;
    meanRealized /= n;

    double covariance = 0;
    double varianceEstimate = 0;
    double varianceRealized = 0;
    for (double[] pair : pairs) {
      double de = pair[0] - meanEstimate;
      double dr = pair[1] - meanRealized;
      covariance += de * dr;
      varianceEstimate += de * de;
      varianceRealized += dr * dr;
    }
    double correlation =
        varianceEstimate == 0 || varianceRealized == 0
            ? 0
            : covariance / Math.sqrt(varianceEstimate * varianceRealized);

    // The values at the origin say more than the means: they are the estimate and the truth for
    // the whole route, so their ratio is the one a reader can reason about directly.
    System.out.printf(
        Locale.ROOT,
        "%-10.2f %10.2f %10.2f %8.3f %8.3f %10.2f %7.0f%%%n",
        fallback,
        pairs.get(0)[0],
        pairs.get(0)[1],
        sumRatio / n,
        correlation,
        meanEstimate,
        100.0 * optimistic / n);
  }

  private static List<Cell> pathOf(
      Path corpusRoot, Scenario scenario, Loadout loadout, CobblestoneLogger logger)
      throws IOException {
    List<Cell> cells = new ArrayList<>();
    SearchObserver recorder =
        new SearchObserver() {
          @Override
          public void solved(List<Cell> path) {
            cells.addAll(path);
          }
        };
    new ScenarioRunner(corpusRoot, logger).run(scenario, loadout, recorder);
    return cells;
  }

  private static java.util.Set<Medium> mediumsFor(Loadout loadout) {
    java.util.EnumSet<Medium> mediums = java.util.EnumSet.copyOf(CoarseCost.survival());
    if (loadout.agent().canFly()) {
      mediums.add(Medium.FLY);
    }
    if (loadout.agent().hasBoat() || loadout.agent().inBoat()) {
      mediums.add(Medium.BOAT);
    }
    return mediums;
  }

  /** Profiles sections of a capture on demand, remembering what it has done. */
  private static final class Profiles implements CoarseSearch.SectionProfiles {

    private final Capture capture;
    private final String world;
    private final SectionProfiler profiler = new SectionProfiler();
    private final Map<Long, SectionProfile> cache = new HashMap<>();
    private final Map<Long, MinecraftChunk> chunks = new HashMap<>();

    Profiles(Capture capture, String world) {
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
}
