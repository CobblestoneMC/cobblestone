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
      System.out.printf(
          Locale.ROOT,
          "%s@%s: no path to sample (%s)%n",
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
                  .withLimits(new Scenario.SearchLimits(1.0, 4_000_000, 600_000)),
              loadout,
              SearchObserver.none());
      double estimate = coarse.costToGoal(cell, Long.MAX_VALUE);
      Sample sample = new Sample(cell, estimate, truth.pathCost(), truth.outcome());
      measured.add(sample);
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
