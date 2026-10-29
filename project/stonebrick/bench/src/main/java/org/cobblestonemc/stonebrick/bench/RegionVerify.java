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
import java.util.List;
import java.util.Locale;
import org.cobblestonemc.Cell;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.DomainBounds;
import org.cobblestonemc.SearchObserver;
import org.cobblestonemc.minecraft.regions.MinecraftTerrain;
import org.cobblestonemc.minecraft.regions.RegionClass;
import org.cobblestonemc.regions.RegionGraph;
import org.cobblestonemc.regions.RegionSearch;
import org.cobblestonemc.regions.RegionSpec;
import org.cobblestonemc.regions.RegionStore;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.cobblestonemc.stonebrick.platform.Capture;

/**
 * Checks the region layer's estimate against the cost it claims to estimate. It verifies the
 * estimate, not the routes: the search's paths are the benchmark's to measure.
 *
 * <p><b>The ground truth is one Dijkstra.</b> The route is solved once with no estimate at all,
 * which finds the genuinely cheapest path. Every stretch of a cheapest path, from a cell on it to
 * the destination, is itself cheapest, so what remains from each of its cells is the total less
 * what the steps before it cost. Samples are taken along that path, which is also where a sound
 * estimate matters: one that pushed the search off the cheapest route would show there, and would
 * not on the route it pushed the search onto.
 *
 * <p>The estimate is the region search's own answer, before the search's heuristic weight: the
 * weight is a tuning choice made against this measurement, not part of what is measured.
 *
 * <p>What matters in the output is the <b>over</b> column: estimates above the true cost. A* is
 * only guaranteed to return an optimal path when the estimate never exceeds what remains, and while
 * this search runs weighted — so optimality is already traded away deliberately — an estimate that
 * is wrong in that direction is trading away more than the weight says it is, invisibly. Being
 * under is safe: it costs expansions, not correctness.
 */
final class RegionVerify {

  private RegionVerify() {}

  /**
   * The property a suite sets on each route's JVM, so the route also prints its ratios on a line of
   * their own for the suite to gather.
   */
  static final String RATIOS_PROPERTY = "bench.verifyRegions.ratios";

  /** How the line of ratios a route prints for its suite begins. */
  static final String RATIOS_PREFIX = "ratios:";

  /** Dijkstra expands everything, so it needs far more room than a normal solve. */
  private static final Scenario.SearchLimits DIJKSTRA_LIMITS =
      new Scenario.SearchLimits(1.0, 4_000_000, 600_000);

  /** One sampled cell: what the region layer said, and what the route actually costs from there. */
  private record Sample(Cell cell, double estimate, double optimal) {

    /**
     * Whether this sample says anything about the estimate's quality.
     *
     * <p>A cell almost at the destination has almost nothing left to cost, so its ratio is a
     * division by near-zero: it swings wildly, means nothing, and one of them is enough to make the
     * whole average useless.
     */
    boolean informative() {
      return optimal > 1.0;
    }

    double ratio() {
      return estimate / optimal;
    }
  }

  /**
   * Samples cells along a scenario's path and reports how the region estimate compares.
   *
   * @param corpusRoot the corpus
   * @param scenario the route
   * @param loadout the traveler
   * @param samples how many cells to check
   * @param logger where the searches log
   * @return the ratio of estimate to true cost at every informative sample
   * @throws IOException if the capture cannot be read
   */
  static List<Double> run(
      Path corpusRoot, Scenario scenario, Loadout loadout, int samples, CobblestoneLogger logger)
      throws IOException {
    if (!scenario.warps().isEmpty()) {
      // The region layer prices walking, not teleports: against a route that warps, every estimate
      // before the warp reads as over, and says nothing about the layer.
      System.out.printf(
          Locale.ROOT, "%s@%s: warps, which regions do not price%n", scenario.id(), loadout.name());
      return List.of();
    }
    ScenarioRunner runner = new ScenarioRunner(corpusRoot, logger);
    RunResult truth =
        runner.run(
            scenario
                .withHeuristic(Scenario.Heuristic.ZERO)
                .withHeuristicWeight(1.0)
                .withLimits(DIJKSTRA_LIMITS),
            loadout,
            SearchObserver.none());
    var cheapest = runner.lastPath();
    if (cheapest == null) {
      System.out.printf(
          Locale.ROOT,
          "%s@%s: no path to sample (Dijkstra: %s)%n",
          scenario.id(),
          loadout.name(),
          truth.outcome());
      return List.of();
    }
    // Each cell of the cheapest path, and what remains from it.
    List<Cell> path = new ArrayList<>();
    List<Double> remaining = new ArrayList<>();
    double left = cheapest.cost();
    path.add(cheapest.origin().cell());
    remaining.add(left);
    for (var step : cheapest.steps()) {
      left -= step.cost();
      path.add(step.position().cell());
      remaining.add(Math.max(0, left));
    }

    Capture capture = Capture.load(CorpusLayout.at(corpusRoot).capture(scenario.capture()).root());
    // Focused on the route's start, as the heuristic's own search is.
    RegionSearch<RegionClass> regions =
        new RegionSearch<>(
            new RegionGraph<>(
                RegionStore.inMemory(
                    new RegionSpec<>(
                        MinecraftTerrain.of(capture.environment(scenario.world())),
                        DomainBounds.vertical(
                            capture.minY(scenario.world()), capture.maxY(scenario.world())),
                        ScenarioRunner.REGION_DETOUR)),
                new DirectCaptureChunks(capture, scenario.world())),
            loadout.regionAgent(capture.environment(scenario.world())),
            path.get(0),
            // The destination the route was solved to, area and all: measured to its center
            // instead, an area's estimates read as over the cost the whole way there.
            scenario::arrives,
            scenario::nearestArrival);

    System.out.printf(Locale.ROOT, "%n%s@%s%n", scenario.id(), loadout.name());
    System.out.printf(
        Locale.ROOT, "  %-22s %10s %10s %8s%n", "cell", "estimate", "optimal", "ratio");

    List<Sample> measured = new ArrayList<>();
    for (int i = 0; i < samples; i++) {
      int at = (int) ((long) i * (path.size() - 1) / Math.max(1, samples - 1));
      Cell cell = path.get(at);
      Sample sample = new Sample(cell, regions.costToGoal(cell), remaining.get(at));
      measured.add(sample);
      System.out.printf(
          Locale.ROOT,
          "  %-22s %10.2f %10.2f %8s%n",
          cell.x() + "," + cell.y() + "," + cell.z(),
          sample.estimate(),
          sample.optimal(),
          sample.informative() ? String.format(Locale.ROOT, "%.3f", sample.ratio()) : "-");
    }
    summarise(measured);
    List<Double> ratios = measured.stream().filter(Sample::informative).map(Sample::ratio).toList();
    if (!ratios.isEmpty()) {
      System.out.println(header(ratios.size() + " informative samples"));
      System.out.println("  " + band(ratios));
    }
    if (System.getProperty(RATIOS_PROPERTY) != null) {
      StringBuilder line = new StringBuilder(RATIOS_PREFIX);
      ratios.forEach(ratio -> line.append(' ').append(ratio));
      System.out.println(line);
    }
    return ratios;
  }

  private static String header(String note) {
    return String.format(
        Locale.ROOT,
        "  %8s %8s %8s %8s %8s   (%s)",
        "mean",
        "worst",
        ">=0.67",
        ">=0.5",
        "over",
        note);
  }

  /** One row of the band table: where the ratios sit against the floors two weights set. */
  static String band(List<Double> ratios) {
    double sum = 0;
    double worst = 0;
    int floor67 = 0;
    int floor50 = 0;
    int over = 0;
    for (double ratio : ratios) {
      sum += ratio;
      worst = Math.max(worst, ratio);
      floor67 += ratio >= 1 / 1.5 ? 1 : 0;
      floor50 += ratio >= 1 / 2.0 ? 1 : 0;
      over += ratio > 1.0001 ? 1 : 0;
    }
    int n = Math.max(1, ratios.size());
    return String.format(
        Locale.ROOT,
        "%8.3f %8.3f %7.0f%% %7.0f%% %7.0f%%",
        sum / n,
        worst,
        100.0 * floor67 / n,
        100.0 * floor50 / n,
        100.0 * over / n);
  }

  /**
   * Prints the band across every route verified.
   *
   * @param ratios every route's informative ratios
   */
  static void aggregate(List<Double> ratios) {
    if (ratios.isEmpty()) {
      return;
    }
    System.out.printf(
        Locale.ROOT, "%nAcross every route, informative samples (the band: >= 1/weight, <= 1)%n");
    System.out.println(header("n=" + ratios.size()));
    System.out.println("  " + band(ratios));
  }

  private static void summarise(List<Sample> samples) {
    List<Sample> usable = samples.stream().filter(Sample::informative).toList();
    if (usable.isEmpty()) {
      System.out.println("  nothing measurable: no sample solved far enough out to be informative");
      return;
    }
    long over = usable.stream().filter(s -> s.estimate() > s.optimal() * 1.0001).count();
    double worst = usable.stream().mapToDouble(Sample::ratio).max().orElse(0);
    double mean = usable.stream().mapToDouble(Sample::ratio).average().orElse(0);
    System.out.printf(
        Locale.ROOT,
        "  %d/%d informative; mean ratio %.3f, worst %.3f, over-estimates %d%n",
        usable.size(),
        samples.size(),
        mean,
        worst,
        over);
  }
}
