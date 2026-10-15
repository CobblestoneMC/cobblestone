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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.cobblestonemc.SearchObserver;

/**
 * Says whether a new scenario is worth keeping, while whoever picked it is still standing there.
 *
 * <p>A scenario is only useful if the bench can measure it, and that is not visible from the two
 * points. Its terrain has to be captured, and the capture has to be wide enough for the routes the
 * searches actually take. Each loadout should solve it, and the route should be hard enough that
 * the heuristics differ. And {@code verify} needs Dijkstra's true optimum, which costs far more
 * than a solve and is out of reach on a long or open route. All of that is found out here, the way
 * the bench itself would.
 *
 * <p><b>The capture grows until the searches stay inside it</b>, exactly as {@code bench run} grows
 * {@code needed-chunks.yml}: whatever a run read beyond its capture is what the capture lacks.
 *
 * <p><b>Dijkstra is the same fine search with no heuristic at weight 1</b>, priced by the same
 * movement modes as production, so its cost is the true optimum under the costs the game is
 * navigated by. It is the expensive part, so its cell budget is bounded by the heap there is.
 */
public final class ScenarioCheck {

  /** Captures terrain on request. */
  @FunctionalInterface
  public interface Terrain {
    /**
     * Captures whatever chunks of the capsule are not captured yet, returning when they are.
     *
     * @param capsule the chunks needed
     * @throws IOException if the capture fails
     * @throws InterruptedException if waiting for it is interrupted
     */
    void capture(NeededChunks capsule) throws IOException, InterruptedException;
  }

  /** How many times the capture may grow before the check gives up on it. */
  static final int MAX_ROUNDS = 6;

  /** {@code verify}'s Dijkstra cell limit: a route it cannot finish within this is unmeasurable. */
  static final int DIJKSTRA_CELLS = 4_000_000;

  /** Dijkstra's time limit here: shorter than {@code verify}'s, since someone is waiting. */
  static final long DIJKSTRA_MILLIS = 180_000;

  /**
   * Roughly what one visited cell holds, to keep Dijkstra inside the heap.
   *
   * <p>Per cell visited, which is fewer than the cells held open: in open air Dijkstra holds
   * several open for each one it visits. Deliberately generous, since a smaller budget only means a
   * route is reported as too big a little early.
   */
  static final long BYTES_PER_CELL = 2_000;

  /** Fewer nodes than this for every heuristic and loadout, and the route tells nothing apart. */
  static final long EASY_NODES = 300;

  /** A solve slower than this is worth a warning. */
  static final long SLOW_MILLIS = 10_000;

  private ScenarioCheck() {}

  /** One loadout's results. */
  private record Row(Loadout loadout, RunResult regions, RunResult average) {}

  /**
   * Checks a scenario, reporting as it goes.
   *
   * @param corpusRoot the corpus the scenario and its capture live in
   * @param scenario the scenario
   * @param loadouts every loadout; those that do not apply to the scenario are skipped
   * @param terrain captures terrain for the scenario
   * @param out where report lines go
   * @return whether the bench can use the scenario as it stands
   * @throws IOException if the corpus cannot be read or written
   * @throws InterruptedException if waiting for a capture is interrupted
   */
  public static boolean run(
      Path corpusRoot,
      Scenario scenario,
      List<Loadout> loadouts,
      Terrain terrain,
      Consumer<String> out)
      throws IOException, InterruptedException {
    List<Loadout> applicable = loadouts.stream().filter(l -> l.appliesTo(scenario)).toList();
    double dx = scenario.destination().x() - scenario.origin().x();
    double dy = scenario.destination().y() - scenario.origin().y();
    double dz = scenario.destination().z() - scenario.origin().z();
    out.accept(
        String.format(
            Locale.ROOT,
            "Checking %s: %.0f blocks apart (%.0f across, %+.0f up), %d loadout(s): %s",
            scenario.id(),
            Math.sqrt(dx * dx + dy * dy + dz * dz),
            Math.sqrt(dx * dx + dz * dz),
            dy,
            applicable.size(),
            String.join(", ", applicable.stream().map(Loadout::name).toList())));
    if (applicable.isEmpty()) {
      out.accept("  No loadout applies to its tags " + scenario.tags() + "; nothing to check.");
      return false;
    }

    Path manifest = corpusRoot.resolve(NeededChunks.FILE_NAME);
    Map<String, NeededChunks> needed = new LinkedHashMap<>(NeededChunks.read(manifest));
    NeededChunks capsule = needed.getOrDefault(scenario.id(), NeededChunks.initial(scenario));
    ScenarioRunner runner = new ScenarioRunner(corpusRoot, new BenchMain.QuietLogger());

    List<Row> rows = new ArrayList<>();
    boolean contained = false;
    for (int round = 1; round <= MAX_ROUNDS && !contained; round++) {
      out.accept(
          String.format(
              Locale.ROOT,
              "  Capturing terrain: %,d chunks (%d either side of the line)",
              capsule.chunkCount(),
              capsule.radiusChunks()));
      terrain.capture(capsule);
      rows.clear();
      NeededChunks grown = capsule;
      int outside = 0;
      for (Loadout loadout : applicable) {
        RunResult regions =
            runner.run(
                scenario.withHeuristic(Scenario.Heuristic.REGIONS), loadout, SearchObserver.none());
        RunResult average =
            runner.run(
                scenario.withHeuristic(Scenario.Heuristic.RUNNING_AVERAGE),
                loadout,
                SearchObserver.none());
        for (RunResult result : List.of(regions, average)) {
          for (long packed : result.missingChunks()) {
            grown = grown.including((int) (packed >> 32), (int) packed);
          }
          outside += result.missingChunks().size();
        }
        rows.add(new Row(loadout, regions, average));
      }
      // Missing terrain inside the capsule is a capture that came back with holes, which another
      // round fills just the same.
      contained = outside == 0;
      if (!contained) {
        out.accept(
            grown.equals(capsule)
                ? "  The searches read chunks the capture lacks; capturing them"
                : String.format(
                    Locale.ROOT,
                    "  The searches went past the capture; widening it to %d either side",
                    grown.radiusChunks()));
        capsule = grown;
      }
    }
    if (!capsule.equals(needed.get(scenario.id()))) {
      needed.put(scenario.id(), capsule);
      NeededChunks.write(manifest, needed);
    }
    if (!contained) {
      out.accept(
          "  ! The searches still leave the capture after "
              + MAX_ROUNDS
              + " rounds. The route may be much longer than the line between its ends.");
    }

    out.accept("  Loadout             REGIONS                          RUNNING_AVERAGE");
    for (Row row : rows) {
      out.accept(
          String.format(
              Locale.ROOT,
              "  %-19s %-32s %s",
              row.loadout().name(),
              summary(row.regions()),
              summary(row.average())));
    }

    List<String> warnings = new ArrayList<>();
    boolean usable = contained;
    List<String> unsolved =
        rows.stream()
            .filter(row -> !row.regions().outcome().equals("success"))
            .map(row -> row.loadout().name())
            .toList();
    if (unsolved.size() == rows.size()) {
      warnings.add("REGIONS solves it for no loadout. Is the destination reachable at all?");
      usable = false;
    } else if (!unsolved.isEmpty()) {
      warnings.add("REGIONS does not solve it for " + String.join(", ", unsolved) + ".");
    }
    if (rows.stream()
        .allMatch(
            row ->
                row.regions().nodesExpanded() < EASY_NODES
                    && row.average().nodesExpanded() < EASY_NODES)) {
      warnings.add(
          "Every search finished in under "
              + EASY_NODES
              + " nodes: too easy to tell heuristics apart. A longer or harder route says more.");
    }
    for (Row row : rows) {
      if (row.regions().realMillis() > SLOW_MILLIS) {
        warnings.add(
            String.format(
                Locale.ROOT,
                "REGIONS took %.1f s for %s.",
                row.regions().realMillis() / 1000.0,
                row.loadout().name()));
      }
    }

    long budget = dijkstraBudget();
    out.accept(
        String.format(
            Locale.ROOT,
            "  Dijkstra, for verify (up to %,d cells%s):",
            budget,
            budget < DIJKSTRA_CELLS ? ", less than verify's because of the heap" : ""));
    for (Row row : rows) {
      RunResult dijkstra;
      try {
        dijkstra =
            runner.run(
                scenario
                    .withHeuristic(Scenario.Heuristic.ZERO)
                    .withHeuristicWeight(1.0)
                    .withLimits(new Scenario.SearchLimits(1.0, (int) budget, DIJKSTRA_MILLIS)),
                row.loadout(),
                SearchObserver.none());
      } catch (OutOfMemoryError tooBig) {
        // Its whole search is garbage the moment this unwinds, so there is room again for the
        // next loadout; and running out is itself the answer.
        out.accept(
            String.format(
                Locale.ROOT,
                "    %-17s ran out of memory: verify cannot measure this route from its start",
                row.loadout().name()));
        continue;
      }
      out.accept("    " + dijkstraLine(row, dijkstra));
      if (dijkstra.outcome().equals("success") && !dijkstra.missingChunks().isEmpty()) {
        warnings.add(
            String.format(
                Locale.ROOT,
                "Dijkstra for %s reached %,d chunks past the capture; verify's optimum there"
                    + " treats them as walls.",
                row.loadout().name(),
                dijkstra.missingChunks().size()));
      }
    }

    for (String warning : warnings) {
      out.accept("  ! " + warning);
    }
    out.accept(usable ? "  Usable." : "  Not usable as it stands.");
    return usable;
  }

  private static String summary(RunResult result) {
    if (!result.outcome().equals("success")) {
      return String.format(Locale.ROOT, "%s, %,d nodes", result.outcome(), result.nodesExpanded());
    }
    return String.format(
        Locale.ROOT,
        "cost %.1f, %,d nodes, %.1f s",
        result.pathCost(),
        result.nodesExpanded(),
        result.realMillis() / 1000.0);
  }

  private static String dijkstraLine(Row row, RunResult dijkstra) {
    String name = String.format(Locale.ROOT, "%-17s", row.loadout().name());
    if (!dijkstra.outcome().equals("success")) {
      return String.format(
          Locale.ROOT,
          "%s %s after %,d cells, %.0f s: verify cannot measure this route from its start",
          name,
          dijkstra.outcome(),
          dijkstra.nodesExpanded(),
          dijkstra.realMillis() / 1000.0);
    }
    double optimum = dijkstra.pathCost();
    String regions =
        row.regions().outcome().equals("success")
            ? String.format(
                Locale.ROOT, "REGIONS %+.1f%%", 100 * (row.regions().pathCost() / optimum - 1))
            : "REGIONS unsolved";
    String average =
        row.average().outcome().equals("success")
            ? String.format(
                Locale.ROOT,
                "RUNNING_AVERAGE %+.1f%%",
                100 * (row.average().pathCost() / optimum - 1))
            : "RUNNING_AVERAGE unsolved";
    return String.format(
        Locale.ROOT,
        "%s optimum %.1f in %,d cells, %.0f s; %s, %s",
        name,
        optimum,
        dijkstra.nodesExpanded(),
        dijkstra.realMillis() / 1000.0,
        regions,
        average);
  }

  /** As many cells as fit in about half the heap that is free, up to {@code verify}'s limit. */
  private static long dijkstraBudget() {
    Runtime runtime = Runtime.getRuntime();
    System.gc();
    long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    return Math.max(10_000, Math.min(DIJKSTRA_CELLS, free / 2 / BYTES_PER_CELL));
  }
}
