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
import org.cobblestonemc.SearchObserver;
import org.cobblestonemc.minecraft.regions.MinecraftTerrain;
import org.cobblestonemc.regions.RegionGraph;
import org.cobblestonemc.regions.RegionSearch;
import org.cobblestonemc.regions.RegionSpec;
import org.cobblestonemc.regions.RegionStore;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.cobblestonemc.stonebrick.platform.Capture;

/**
 * Checks the region estimate against the cost it claims to estimate.
 *
 * <p>The ground truth is measured. For each sampled cell the route is solved again from that cell
 * with no heuristic at all — Dijkstra, weight 1 — which returns the genuine optimal cost to the
 * destination. That is expensive, which is why this samples rather than sweeps, and it is the only
 * number worth comparing an estimate against.
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
final class Verify {

  private Verify() {}

  /**
   * The property a suite sets on each route's JVM, so the route also prints its ratios on a line of
   * their own for the suite to gather.
   */
  static final String RATIOS_PROPERTY = "bench.verify.ratios";

  /** How the line of ratios a route prints for its suite begins. */
  static final String RATIOS_PREFIX = "ratios:";

  /** Dijkstra expands everything, so it needs far more room than a normal solve. */
  private static final Scenario.SearchLimits DIJKSTRA_LIMITS =
      new Scenario.SearchLimits(1.0, 4_000_000, 600_000);

  /** One sampled cell: what the region layer said, and what the route actually costs from there. */
  private record Sample(Cell cell, double estimate, double optimal, String outcome) {

    boolean measured() {
      return outcome.equals("success");
    }

    /**
     * Whether this sample says anything about the estimate's quality.
     *
     * <p>A cell almost at the destination has almost nothing left to cost, so its ratio is a
     * division by near-zero: it swings wildly, means nothing, and one of them is enough to make the
     * whole average useless.
     */
    boolean informative() {
      return measured() && optimal > 1.0;
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
   * @param loadout the traveller
   * @param samples how many cells to check
   * @param logger where the searches log
   * @return the ratio of estimate to true cost at every informative sample
   * @throws IOException if the capture cannot be read
   */
  static List<Double> run(
      Path corpusRoot, Scenario scenario, Loadout loadout, int samples, CobblestoneLogger logger)
      throws IOException {
    ScenarioRunner runner = new ScenarioRunner(corpusRoot, logger);

    List<Cell> path = new ArrayList<>();
    RunResult found =
        runner.run(
            scenario.withHeuristic(Scenario.Heuristic.REGIONS),
            loadout,
            new SearchObserver() {
              @Override
              public void solved(List<Cell> cells) {
                path.addAll(cells);
              }
            });
    if (path.isEmpty()) {
      // The routes the estimate is worst on are the ones the weighted search cannot finish, so
      // sampling only where it succeeded would measure the layer everywhere except where it
      // matters. Dijkstra's own path is as good a place to sample, and is the truth besides.
      runner.run(
          scenario
              .withHeuristic(Scenario.Heuristic.ZERO)
              .withHeuristicWeight(1.0)
              .withLimits(DIJKSTRA_LIMITS),
          loadout,
          new SearchObserver() {
            @Override
            public void solved(List<Cell> cells) {
              path.addAll(cells);
            }
          });
    }
    if (path.isEmpty()) {
      System.out.printf(
          Locale.ROOT,
          "%s@%s: no path to sample (%s, and Dijkstra found none either)%n",
          scenario.id(),
          loadout.name(),
          found.outcome());
      return List.of();
    }

    Capture capture = Capture.load(CorpusLayout.at(corpusRoot).capture(scenario.capture()).root());
    // Focused on the route's start, as the heuristic's own search is.
    RegionSearch regions =
        new RegionSearch(
            new RegionGraph<>(
                RegionStore.inMemory(
                    new RegionSpec<>(
                        MinecraftTerrain.of(capture.environment(scenario.world())),
                        capture.minY(scenario.world()),
                        capture.maxY(scenario.world()),
                        ScenarioRunner.REGION_DETOUR)),
                new DirectChunkColumns(capture, scenario.world())),
            loadout.regionAgent(capture.environment(scenario.world())),
            path.get(0),
            // The destination the route was solved to, area and all: measured to its centre
            // instead, an area's estimates read as over the cost the whole way there.
            scenario::arrives,
            scenario::nearestArrival);

    System.out.printf(Locale.ROOT, "%n%s@%s%n", scenario.id(), loadout.name());
    System.out.printf(
        Locale.ROOT, "  %-22s %10s %10s %8s%n", "cell", "estimate", "optimal", "ratio");

    List<Sample> measured = new ArrayList<>();
    for (int i = 0; i < samples; i++) {
      Cell cell = path.get((int) ((long) i * (path.size() - 1) / Math.max(1, samples - 1)));
      // Dijkstra from here: no estimate, no weight, so what comes back is the real thing.
      RunResult truth =
          runner.run(
              scenario
                  .withOrigin(cell)
                  .withHeuristic(Scenario.Heuristic.ZERO)
                  .withHeuristicWeight(1.0)
                  // A diagnostic run offline, not something a budget applies to.
                  .withLimits(DIJKSTRA_LIMITS),
              loadout,
              SearchObserver.none());
      Sample sample = new Sample(cell, regions.costToGoal(cell), truth.pathCost(), truth.outcome());
      measured.add(sample);
      System.out.printf(
          Locale.ROOT,
          "  %-22s %10.2f %10s %8s%n",
          cell.x() + "," + cell.y() + "," + cell.z(),
          sample.estimate(),
          sample.measured()
              ? String.format(Locale.ROOT, "%.2f", sample.optimal())
              : truth.outcome(),
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
