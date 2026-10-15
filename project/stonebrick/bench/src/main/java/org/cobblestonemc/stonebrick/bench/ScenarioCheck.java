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
 * <p><b>A scenario is turned away only if the bench cannot measure it</b>: when the terrain the
 * routes need cannot be captured. A heuristic failing is not a reason, it is a result. A failed run
 * still says how near it came and how many nodes it spent getting there, and when every heuristic
 * fails, the one that came closer did better.
 *
 * <p><b>The capture grows until every route that arrives stays inside it</b>, as {@code bench run}
 * grows {@code needed-chunks.yml}: whatever an arriving run read beyond its capture is what the
 * capture lacks. A failed run's wandering does not grow it. A search that gives up has often read
 * in every direction, and chasing that would capture a field; its reads past the edge are walls to
 * it, which it would have given up at anyway.
 *
 * <p>Everything else is reported, not judged: whether each heuristic arrives, at what cost, how
 * close a failure came, whether the route is too easy to tell heuristics apart, and whether
 * Dijkstra can reach the route's true optimum, which costs far more than a solve and is out of
 * reach on a long or open route.
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

  /** Dijkstra's cell limit: a route it cannot finish within this has no optimum to compare to. */
  static final int DIJKSTRA_CELLS = 4_000_000;

  /** Dijkstra's time limit here: kept short, since someone is waiting. */
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
  /** One loadout's results, by heuristic. */
  private record Row(Loadout loadout, Map<Scenario.Heuristic, RunResult> results) {}

  /** Every estimate production can run: each one a scenario is checked under. */
  private static List<Scenario.Heuristic> heuristics() {
    return java.util.Arrays.stream(Scenario.Heuristic.values())
        .filter(heuristic -> heuristic.production() != null)
        .toList();
  }

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
        Map<Scenario.Heuristic, RunResult> results = new LinkedHashMap<>();
        for (Scenario.Heuristic heuristic : heuristics()) {
          RunResult result =
              runner.run(scenario.withHeuristic(heuristic), loadout, SearchObserver.none());
          results.put(heuristic, result);
          if (!result.outcome().equals("success")) {
            continue; // its wandering is not terrain the route needs
          }
          for (long packed : result.missingChunks()) {
            grown = grown.including((int) (packed >> 32), (int) packed);
          }
          outside += result.missingChunks().size();
        }
        rows.add(new Row(loadout, results));
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
          "  ! Routes that arrive still leave the capture after "
              + MAX_ROUNDS
              + " rounds: the capture cannot keep up with the route.");
    }

    StringBuilder header = new StringBuilder(String.format(Locale.ROOT, "  %-19s", "Loadout"));
    for (Scenario.Heuristic heuristic : heuristics()) {
      header.append(String.format(Locale.ROOT, " %-44s", heuristic));
    }
    out.accept(header.toString().stripTrailing());
    for (Row row : rows) {
      StringBuilder line =
          new StringBuilder(String.format(Locale.ROOT, "  %-19s", row.loadout().name()));
      for (RunResult result : row.results().values()) {
        line.append(String.format(Locale.ROOT, " %-44s", summary(result)));
      }
      out.accept(line.toString().stripTrailing());
    }

    List<String> warnings = new ArrayList<>();
    boolean usable = contained;
    boolean anyArrived =
        rows.stream()
            .flatMap(row -> row.results().values().stream())
            .anyMatch(result -> result.outcome().equals("success"));
    if (!anyArrived) {
      warnings.add(
          "No search arrived for any loadout. Still measurable, by how close each came; but check"
              + " that the destination can be reached.");
    }
    for (Row row : rows) {
      for (Map.Entry<Scenario.Heuristic, RunResult> entry : row.results().entrySet()) {
        RunResult result = entry.getValue();
        if (!result.outcome().equals("success") && !result.missingChunks().isEmpty()) {
          warnings.add(
              String.format(
                  Locale.ROOT,
                  "%s for %s wandered %,d chunks past the capture before giving up; those are"
                      + " walls to it.",
                  entry.getKey(),
                  row.loadout().name(),
                  result.missingChunks().size()));
        }
      }
    }
    if (rows.stream()
        .flatMap(row -> row.results().values().stream())
        .allMatch(result -> result.nodesExpanded() < EASY_NODES)) {
      warnings.add(
          "Every search finished in under "
              + EASY_NODES
              + " nodes: too easy to tell heuristics apart. A longer or harder route says more.");
    }
    for (Row row : rows) {
      for (Map.Entry<Scenario.Heuristic, RunResult> entry : row.results().entrySet()) {
        if (entry.getValue().realMillis() > SLOW_MILLIS) {
          warnings.add(
              String.format(
                  Locale.ROOT,
                  "%s took %.1f s for %s.",
                  entry.getKey(),
                  entry.getValue().realMillis() / 1000.0,
                  row.loadout().name()));
        }
      }
    }

    long budget = dijkstraBudget();
    out.accept(
        String.format(
            Locale.ROOT,
            "  Dijkstra, for the true optimum (up to %,d cells%s):",
            budget,
            budget < DIJKSTRA_CELLS ? ", fewer than it could take because of the heap" : ""));
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
                "    %-17s ran out of memory: no optimum for this route from its start",
                row.loadout().name()));
        continue;
      }
      out.accept("    " + dijkstraLine(row, dijkstra));
      if (dijkstra.outcome().equals("success") && !dijkstra.missingChunks().isEmpty()) {
        warnings.add(
            String.format(
                Locale.ROOT,
                "Dijkstra for %s reached %,d chunks past the capture; its optimum there"
                    + " treats them as walls.",
                row.loadout().name(),
                dijkstra.missingChunks().size()));
      }
    }

    for (String warning : warnings) {
      out.accept("  ! " + warning);
    }
    out.accept(
        usable
            ? "  Usable."
            : "  Not usable as it stands: the terrain its routes need could not be captured.");
    return usable;
  }

  private static String summary(RunResult result) {
    if (!result.outcome().equals("success")) {
      return String.format(
          Locale.ROOT,
          "%s, %,d nodes, %.0f blocks short",
          result.outcome(),
          result.nodesExpanded(),
          result.closestBlocks());
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
          "%s %s after %,d cells, %.0f s: no optimum for this route from its start",
          name,
          dijkstra.outcome(),
          dijkstra.nodesExpanded(),
          dijkstra.realMillis() / 1000.0);
    }
    double optimum = dijkstra.pathCost();
    List<String> against = new ArrayList<>();
    for (Map.Entry<Scenario.Heuristic, RunResult> entry : row.results().entrySet()) {
      RunResult result = entry.getValue();
      against.add(
          result.outcome().equals("success")
              ? String.format(
                  Locale.ROOT,
                  "%s %+.1f%%",
                  entry.getKey(),
                  100 * (result.pathCost() / optimum - 1))
              : entry.getKey() + " unsolved");
    }
    return String.format(
        Locale.ROOT,
        "%s optimum %.1f in %,d cells, %.0f s; %s",
        name,
        optimum,
        dijkstra.nodesExpanded(),
        dijkstra.realMillis() / 1000.0,
        String.join(", ", against));
  }

  /** As many cells as fit in about half the heap that is free, up to {@link #DIJKSTRA_CELLS}. */
  private static long dijkstraBudget() {
    Runtime runtime = Runtime.getRuntime();
    System.gc();
    long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    return Math.max(10_000, Math.min(DIJKSTRA_CELLS, free / 2 / BYTES_PER_CELL));
  }
}
