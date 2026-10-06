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
import org.cobblestonemc.minecraft.lod.CoarseCost;
import org.cobblestonemc.minecraft.lod.CoarseSearch;
import org.cobblestonemc.stonebrick.format.CorpusLayout;
import org.cobblestonemc.stonebrick.platform.Capture;

/**
 * Checks that a coarse estimate is a bound on the cost it claims to estimate.
 *
 * <p><b>Why this is not {@code bench h3}.</b> That report compares the estimate against {@code
 * total - spent}, where the spend is the path's total cost divided evenly over its steps. That is a
 * straight line from the total down to zero, not the real remaining cost: a route that walks, then
 * swims, then mines has steps differing several-fold, so the comparison mostly confirms that two
 * numbers both fall along a path. It cannot see an inadmissible estimate at all.
 *
 * <p>Here the ground truth is measured. For each sampled cell the route is solved again from that
 * cell with no heuristic at all — Dijkstra, weight 1 — which returns the genuine optimal cost to
 * the destination. That is expensive, which is why this samples rather than sweeps, and it is the
 * only number worth comparing an estimate against.
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
   * How far from the destination a sample has to be to count against the band.
   *
   * <p>{@code Tier2Search}'s endgame radius: inside it the search decays its weight towards 1, so
   * the floor the weight sets does not apply there, and the ratios are noisy divisions by small
   * remaining costs besides.
   */
  static final double FAR = 64.0;

  /** The far samples' ratios, keyed by "blend diagonal", for aggregating across routes. */
  static final java.util.Map<String, List<Double>> FAR_RATIOS = new java.util.LinkedHashMap<>();

  /** One sampled cell: what the coarse layer said, and what the route actually costs from there. */
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
   * Samples cells along a scenario's path and reports how the coarse estimate compares.
   *
   * @param corpusRoot the corpus
   * @param scenario the route
   * @param loadout the traveller
   * @param samples how many cells to check
   * @param logger where the searches log
   * @throws IOException if the capture cannot be read
   */
  /** Dijkstra expands everything, so it needs far more room than a normal solve. */
  private static final Scenario.SearchLimits DIJKSTRA_LIMITS =
      new Scenario.SearchLimits(1.0, 4_000_000, 600_000);

  static void run(
      Path corpusRoot, Scenario scenario, Loadout loadout, int samples, CobblestoneLogger logger)
      throws IOException {
    ScenarioRunner runner = new ScenarioRunner(corpusRoot, logger);

    List<Cell> path = new ArrayList<>();
    RunResult found =
        runner.run(
            scenario.withHeuristic(Scenario.Heuristic.COARSE),
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
      return;
    }

    Capture capture = Capture.load(CorpusLayout.at(corpusRoot).capture(scenario.capture()).root());
    CoarseSearch coarse =
        new CoarseSearch(
            new DirectProfiles(capture, scenario.world()),
            CoarseCost.forMediums(loadout.mediums()),
            scenario.destination());

    System.out.printf(Locale.ROOT, "%n%s@%s%n", scenario.id(), loadout.name());
    System.out.printf(
        Locale.ROOT, "  %-22s %10s %10s %8s%n", "cell", "estimate", "optimal", "ratio");

    List<Sample> measured = new ArrayList<>();
    List<Cell> cells = new ArrayList<>();
    List<Double> optimums = new ArrayList<>();
    for (int i = 0; i < samples; i++) {
      Cell cell = path.get((int) ((long) i * (path.size() - 1) / Math.max(1, samples - 1)));
      // Dijkstra from here: no estimate, no weight, so what comes back is the real thing.
      RunResult truth =
          runner.run(
              scenario
                  .withOrigin(cell)
                  .withHeuristic(Scenario.Heuristic.ZERO)
                  .withHeuristicWeight(1.0)
                  // Dijkstra expands everything, so it needs far more room than a normal solve.
                  // This is a diagnostic run offline, not something a budget applies to.
                  .withLimits(DIJKSTRA_LIMITS),
              loadout,
              SearchObserver.none());
      double estimate = coarse.costToGoal(cell, Long.MAX_VALUE);
      Sample sample = new Sample(cell, estimate, truth.pathCost(), truth.outcome());
      measured.add(sample);
      if (sample.informative()) {
        cells.add(cell);
        optimums.add(truth.pathCost());
      }
      System.out.printf(
          Locale.ROOT,
          "  %-22s %10.2f %10s %8s%n",
          cell.x() + "," + cell.y() + "," + cell.z(),
          estimate,
          sample.measured()
              ? String.format(Locale.ROOT, "%.2f", sample.optimal())
              : truth.outcome(),
          sample.informative() ? String.format(Locale.ROOT, "%.3f", sample.ratio()) : "-");
    }
    summarise(measured);
    // Only the far samples go on to the blend comparison and the aggregate: they are the ones the
    // band is about.
    List<Cell> far = new ArrayList<>();
    List<Double> farOptimums = new ArrayList<>();
    for (int i = 0; i < cells.size(); i++) {
      if (cells.get(i).distance(scenario.destination()) >= FAR) {
        far.add(cells.get(i));
        farOptimums.add(optimums.get(i));
      }
    }
    sweepFallback(scenario, loadout, capture, far, farOptimums);
  }

  /**
   * Re-prices the same cells at a range of fallback rates.
   *
   * <p>Free, relative to what it is worth: the costly half of this report is the Dijkstra per
   * sample, and that answer does not depend on the fallback at all. So the optimums are measured
   * once and every candidate rate is scored against them.
   *
   * <p>The rate is what a crossing costs where no medium the agent has reaches -- {@link
   * CoarseCost} calls it the tuning risk in the whole layer, and it is the first suspect for a
   * systematically high estimate, since every partly-covered section pays it on the uncovered
   * share.
   */
  private static void sweepFallback(
      Scenario scenario,
      Loadout loadout,
      Capture capture,
      List<Cell> cells,
      List<Double> optimums) {
    if (cells.isEmpty()) {
      return;
    }
    System.out.printf(
        Locale.ROOT,
        "  %-10s %-10s %8s %8s %8s %8s %8s   (%d samples beyond %.0f blocks)%n",
        "blend",
        "diagonal",
        "mean",
        "worst",
        ">=0.67",
        ">=0.5",
        "over",
        cells.size(),
        FAR);
    for (CoarseCost.Blend rule : CoarseCost.Blend.values()) {
      for (CoarseCost.Diagonal corner : CoarseCost.Diagonal.values()) {
        report(scenario, loadout, capture, cells, optimums, rule, corner);
      }
    }
  }

  private static void report(
      Scenario scenario,
      Loadout loadout,
      Capture capture,
      List<Cell> cells,
      List<Double> optimums,
      CoarseCost.Blend rule,
      CoarseCost.Diagonal corner) {
    {
      CoarseSearch coarse =
          new CoarseSearch(
              new DirectProfiles(capture, scenario.world()),
              CoarseCost.forMediums(loadout.mediums()).withBlend(rule).withDiagonal(corner),
              scenario.destination());
      List<Double> ratios = new ArrayList<>();
      for (int i = 0; i < cells.size(); i++) {
        ratios.add(coarse.costToGoal(cells.get(i), Long.MAX_VALUE) / optimums.get(i));
      }
      FAR_RATIOS.computeIfAbsent(rule + " " + corner, k -> new ArrayList<>()).addAll(ratios);
      System.out.println("  " + band(rule + "", corner + "", ratios));
    }
  }

  /** One row of the band table: where the ratios sit against the floors two weights set. */
  static String band(String rule, String corner, List<Double> ratios) {
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
        "%-10s %-10s %8.3f %8.3f %7.0f%% %7.0f%% %7.0f%%",
        rule,
        corner,
        sum / n,
        worst,
        100.0 * floor67 / n,
        100.0 * floor50 / n,
        100.0 * over / n);
  }

  /** Prints the band table across every route verified, one row per blend. */
  static void aggregate() {
    if (FAR_RATIOS.isEmpty()) {
      return;
    }
    System.out.printf(
        Locale.ROOT,
        "%nAcross every route, samples beyond %.0f blocks (the band: >= 1/weight, <= 1)%n",
        FAR);
    System.out.printf(
        Locale.ROOT,
        "  %-10s %-10s %8s %8s %8s %8s %8s%n",
        "blend",
        "diagonal",
        "mean",
        "worst",
        ">=0.67",
        ">=0.5",
        "over");
    FAR_RATIOS.forEach(
        (key, ratios) -> {
          String[] parts = key.split(" ");
          System.out.println("  " + band(parts[0], parts[1], ratios) + "   n=" + ratios.size());
        });
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
