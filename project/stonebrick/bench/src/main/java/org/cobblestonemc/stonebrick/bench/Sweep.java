/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cobblestonemc.SearchObserver;

/**
 * The nodes-versus-cost tradeoff, measured rather than argued about.
 *
 * <p>A weighted A* does not have a quality; it has a curve. Raising the weight buys fewer expanded
 * nodes with a worse path, and the only honest way to compare two heuristics is to compare their
 * curves — reporting one point from each says more about the two weights that happened to be
 * configured than about the heuristics. That mistake is easy to make and was made here once
 * already, which is why this command exists.
 *
 * <p>Cost is reported as <b>excess over the best path any run in the sweep found</b> for that
 * scenario, not over an optimum: a true optimum would need the exhaustive search this whole layer
 * exists to avoid. The best-seen path is a lower bound that tightens as the sweep widens, so the
 * excess figures are upper bounds on how much quality a weight really gives up.
 *
 * <p>Nodes are aggregated as a <b>geometric</b> mean. Expansion counts across these scenarios span
 * two orders of magnitude, and an arithmetic mean would let {@code deep-cave} alone decide the
 * answer for every route.
 */
final class Sweep {

  /** The weights swept when none are given: production's 1.5 bracketed on both sides. */
  private static final double[] DEFAULT_WEIGHTS = {1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0};

  private Sweep() {}

  /** One scenario run at one (heuristic, weight) point. */
  private record Point(
      String scenario,
      String loadout,
      Scenario.Heuristic heuristic,
      double weight,
      String outcome,
      double cost,
      long nodes,
      int coarseColumns) {

    boolean solved() {
      return outcome.equals("success");
    }
  }

  /**
   * Runs every scenario at every point of the grid and reports the curve.
   *
   * @param scenarios the scenarios to sweep
   * @param options the command line
   * @param runner the runner to solve with
   * @throws IOException if a capture cannot be read
   */
  static void run(
      List<Scenario> scenarios,
      List<Loadout> loadouts,
      BenchMain.Options options,
      ScenarioRunner runner)
      throws IOException {
    double[] weights = options.weights() == null ? DEFAULT_WEIGHTS : options.weights();
    List<Scenario.Heuristic> heuristics =
        options.heuristic() == null
            ? List.of(Scenario.Heuristic.values())
            : List.of(options.heuristic());

    List<Point> points = new ArrayList<>();
    for (Scenario scenario : scenarios) {
      for (Loadout loadout : loadouts) {
        for (Scenario.Heuristic heuristic : heuristics) {
          for (double weight : weights) {
            Scenario variant = scenario.withHeuristic(heuristic).withHeuristicWeight(weight);
            System.out.printf(
                Locale.ROOT,
                "running %s %s w=%.2f...%n",
                scenario.runId(loadout),
                heuristic,
                weight);
            RunResult result = runner.run(variant, loadout, SearchObserver.none());
            CaptureProfiles profiles = runner.lastCoarseProfiles();
            points.add(
                new Point(
                    scenario.runId(loadout),
                    loadout.name(),
                    heuristic,
                    weight,
                    result.outcome(),
                    result.pathCost(),
                    result.nodesExpanded(),
                    heuristic == Scenario.Heuristic.COARSE && profiles != null
                        ? profiles.chunkColumns()
                        : 0));
          }
        }
      }
    }

    System.out.println();
    perScenario(points);
    System.out.println();
    summary(points, heuristics, weights);
  }

  private static void perScenario(List<Point> points) {
    Map<String, Double> best = bestCosts(points);
    String current = null;
    for (Point point : points) {
      if (!point.scenario().equals(current)) {
        current = point.scenario();
        System.out.printf(Locale.ROOT, "%n%s%n", current);
        System.out.printf(
            Locale.ROOT,
            "  %-16s %6s %12s %10s %8s %8s%n",
            "heuristic",
            "w",
            "nodes",
            "cost",
            "excess",
            "columns");
      }
      Double floor = best.get(point.scenario());
      String excess =
          point.solved() && floor != null
              ? String.format(Locale.ROOT, "%+.1f%%", 100 * (point.cost() / floor - 1))
              : "-";
      System.out.printf(
          Locale.ROOT,
          "  %-16s %6.2f %12d %10s %8s %8s%n",
          point.heuristic(),
          point.weight(),
          point.nodes(),
          point.solved() ? String.format(Locale.ROOT, "%.2f", point.cost()) : point.outcome(),
          excess,
          point.coarseColumns() == 0 ? "-" : String.valueOf(point.coarseColumns()));
    }
  }

  /** The cheapest path found for each scenario anywhere in the sweep: our stand-in for optimal. */
  private static Map<String, Double> bestCosts(List<Point> points) {
    Map<String, Double> best = new LinkedHashMap<>();
    for (Point point : points) {
      if (point.solved()) {
        best.merge(point.scenario(), point.cost(), Math::min);
      }
    }
    return best;
  }

  private static void summary(
      List<Point> points, List<Scenario.Heuristic> heuristics, double[] weights) {
    Map<String, Double> best = bestCosts(points);
    List<String> all = points.stream().map(Point::scenario).distinct().toList();
    // Only the scenarios every point in the grid solved. Averaging each row over whatever it
    // happened to finish would reward a weight for giving up on the hard routes: the row that
    // solves seven of fourteen is measured on the seven easy ones. The solved column carries that
    // information separately, where it cannot contaminate the averages.
    List<String> common =
        all.stream()
            .filter(
                scenario ->
                    points.stream().filter(p -> p.scenario().equals(scenario)).allMatch(Point::solved))
            .toList();

    System.out.printf(
        Locale.ROOT,
        "Across the %d of %d scenario(s) every point solved "
            + "(nodes: geometric mean; excess: mean over best seen)%n",
        common.size(),
        all.size());
    System.out.printf(
        Locale.ROOT, "%-16s %6s %12s %10s %8s%n", "heuristic", "w", "nodes", "excess", "solved");
    for (Scenario.Heuristic heuristic : heuristics) {
      for (double weight : weights) {
        List<Point> at =
            points.stream()
                .filter(p -> p.heuristic() == heuristic && p.weight() == weight)
                .toList();
        long solved = at.stream().filter(Point::solved).count();
        List<Point> scored = at.stream().filter(p -> common.contains(p.scenario())).toList();
        double logSum = scored.stream().mapToDouble(p -> Math.log(Math.max(1, p.nodes()))).sum();
        double excess =
            scored.stream()
                .mapToDouble(p -> p.cost() / best.getOrDefault(p.scenario(), p.cost()) - 1)
                .average()
                .orElse(0);
        System.out.printf(
            Locale.ROOT,
            "%-16s %6.2f %12s %10s %5d/%d%n",
            heuristic,
            weight,
            scored.isEmpty()
                ? "-"
                : String.format(Locale.ROOT, "%.0f", Math.exp(logSum / scored.size())),
            scored.isEmpty() ? "-" : String.format(Locale.ROOT, "%+.1f%%", 100 * excess),
            solved,
            all.size());
      }
    }
  }
}
