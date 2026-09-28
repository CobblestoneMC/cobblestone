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
import java.util.Map;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.SearchObserver;

/**
 * The benchmark command line.
 *
 * <pre>
 *   bench run      [--scenario id] [--tier ci|local] [--tag t]   run and compare against baselines
 *   bench accept   [--scenario id] [--tier ci|local]             run and record the results as accepted
 *   bench list                                                   show the corpus
 *   bench profile  --scenario &lt;capture&gt;                          profile a capture's sections
 *   bench h3       [--scenario id]                                coarse estimate vs realized cost
 * </pre>
 *
 * <p>Exits non-zero when a gated metric moved, so CI fails on an unexplained change. That failure
 * is not an accusation — see {@link Comparison} — it is a requirement that somebody look.
 */
public final class BenchMain {

  private BenchMain() {}

  /**
   * Entry point.
   *
   * @param args the command line
   * @throws IOException if the corpus cannot be read
   */
  public static void main(String[] args) throws IOException {
    Options options = Options.parse(args);
    List<Scenario> scenarios = select(Scenario.loadAll(options.scenariosDir()), options);
    List<Loadout> loadouts =
        selectLoadouts(Loadout.loadAll(options.corpusRoot().resolve(Loadout.FILE_NAME)), options);

    if (options.command().equals("h3")) {
      for (Scenario scenario : scenarios) {
        for (Loadout loadout : loadouts) {
          HeuristicAccuracy.run(options.corpusRoot(), scenario, loadout, new QuietLogger());
        }
      }
      return;
    }

    if (options.command().equals("profile")) {
      ProfileReport.run(
          options.corpusRoot(), options.scenarioId() == null ? "smoke" : options.scenarioId());
      return;
    }

    if (scenarios.isEmpty()) {
      System.out.println(
          "No scenarios matched. Looked in " + options.scenariosDir().toAbsolutePath());
      System.exit(options.command().equals("list") ? 0 : 2);
      return;
    }

    switch (options.command()) {
      case "list" -> list(scenarios, loadouts);
      case "accept" -> accept(scenarios, loadouts, options);
      case "run" -> System.exit(run(scenarios, loadouts, options) ? 0 : 1);
      case "sweep" ->
          Sweep.run(
              scenarios,
              loadouts,
              options,
              new ScenarioRunner(options.corpusRoot(), new QuietLogger()));
      default -> {
        System.out.println(
            "Unknown command '" + options.command() + "'. Try run, sweep, accept or list.");
        System.exit(2);
      }
    }
  }

  private static void list(List<Scenario> scenarios, List<Loadout> loadouts) {
    System.out.printf(Locale.ROOT, "%-28s %-6s %-16s %s%n", "id", "tier", "capture", "description");
    for (Scenario scenario : scenarios) {
      System.out.printf(
          Locale.ROOT,
          "%-28s %-6s %-16s %s%n",
          scenario.id(),
          scenario.tier(),
          scenario.capture(),
          scenario.description());
    }
    System.out.printf(Locale.ROOT, "%n%-28s %s%n", "loadout", "description");
    for (Loadout loadout : loadouts) {
      System.out.printf(Locale.ROOT, "%-28s %s%n", loadout.name(), loadout.description());
    }
    System.out.printf(
        Locale.ROOT,
        "%n%d scenarios x %d loadouts = %d runs.%n",
        scenarios.size(),
        loadouts.size(),
        scenarios.size() * loadouts.size());
  }

  /** Applies the {@code --heuristic} and {@code --weight} overrides, if either was given. */
  private static Scenario applyOverrides(Scenario scenario, Options options) {
    Scenario applied =
        options.heuristic() == null ? scenario : scenario.withHeuristic(options.heuristic());
    if (options.weights() != null && options.weights().length > 0) {
      applied = applied.withHeuristicWeight(options.weights()[0]);
    }
    return applied;
  }

  private static boolean run(List<Scenario> scenarios, List<Loadout> loadouts, Options options)
      throws IOException {
    ScenarioRunner runner = new ScenarioRunner(options.corpusRoot(), new QuietLogger());
    Path manifest = options.corpusRoot().resolve(NeededChunks.FILE_NAME);
    Map<String, NeededChunks> needed = new java.util.LinkedHashMap<>(NeededChunks.read(manifest));
    boolean changed = false;
    boolean missing = false;

    List<Comparison.Verdict> verdicts = new ArrayList<>();
    for (Scenario scenario : scenarios) {
      for (Loadout loadout : loadouts) {
        // ASCII: a Windows console defaults to a code page that renders an ellipsis as a
        // replacement character, and the first thing a benchmark prints should not look broken.
        System.out.println("running " + scenario.runId(loadout) + "...");
        RunResult result =
            runner.run(applyOverrides(scenario, options), loadout, SearchObserver.none());
        verdicts.add(
            Comparison.compare(
                result, Baselines.read(options.baselinesDir(), scenario.runId(loadout))));

        // Whatever the run read outside its capture is precisely what the capture is missing.
        // Folding it in here is the whole of the discovery loop: nobody picks a radius, the search
        // reports one.
        //
        // Keyed by the route rather than the run: a flyer and a walker take different paths but
        // read the same capture, so the capsule is their union and the terrain is captured once.
        NeededChunks capsule = needed.getOrDefault(scenario.id(), NeededChunks.initial(scenario));
        NeededChunks grown = capsule;
        for (long packed : result.missingChunks()) {
          grown = grown.including((int) (packed >> 32), (int) packed);
        }
        if (!grown.equals(capsule) || !needed.containsKey(scenario.id())) {
          needed.put(scenario.id(), grown);
          changed = true;
        }
        // Recording a scenario for the first time is not the same event as a run running out of
        // terrain, and saying so either way would cry wolf on every new scenario.
        missing |= !result.missingChunks().isEmpty();
      }
    }

    if (changed) {
      NeededChunks.write(manifest, needed);
      System.out.println();
      if (missing) {
        System.out.println("Terrain is missing. " + manifest + " now covers what the runs");
        System.out.println("reached for; run  ./gradlew captureCorpus  and try again.");
      } else {
        System.out.println("Recorded what these scenarios need in " + manifest + ".");
      }
    }

    System.out.println();
    System.out.print(Comparison.table(verdicts));
    return verdicts.stream().allMatch(Comparison.Verdict::ok);
  }

  private static void accept(List<Scenario> scenarios, List<Loadout> loadouts, Options options)
      throws IOException {
    ScenarioRunner runner = new ScenarioRunner(options.corpusRoot(), new QuietLogger());
    for (Scenario scenario : scenarios) {
      for (Loadout loadout : loadouts) {
        RunResult result = runner.run(scenario, loadout, SearchObserver.none());
        Baselines.accept(options.baselinesDir(), result, options.commit());
        System.out.printf(
            Locale.ROOT,
            "accepted %-40s %s, %,d expanded, cost %.2f%n",
            scenario.runId(loadout),
            result.outcome(),
            result.nodesExpanded(),
            result.pathCost());
      }
    }
    System.out.println(
        "\nReview the baseline diff before committing: it is the record of a decision.");
  }

  /** Narrows the loadouts to the one named by {@code --agent}, if any. */
  private static List<Loadout> selectLoadouts(List<Loadout> all, Options options) {
    if (options.agent() == null) {
      return all;
    }
    List<Loadout> selected =
        all.stream().filter(loadout -> loadout.name().equals(options.agent())).toList();
    if (selected.isEmpty()) {
      System.err.println(
          "No loadout named '"
              + options.agent()
              + "'. Known: "
              + all.stream().map(Loadout::name).toList());
      System.exit(2);
    }
    return selected;
  }

  private static List<Scenario> select(List<Scenario> all, Options options) {
    List<Scenario> selected = new ArrayList<>();
    for (Scenario scenario : all) {
      if (options.scenarioId() != null && !options.scenarioId().equals(scenario.id())) {
        continue;
      }
      if (options.tier() != null && scenario.tier() != options.tier()) {
        continue;
      }
      if (options.tag() != null && !scenario.tags().contains(options.tag())) {
        continue;
      }
      selected.add(scenario);
    }
    return selected;
  }

  /** The command line, parsed. */
  record Options(
      String command,
      Path corpusRoot,
      Path scenariosDir,
      Path baselinesDir,
      String scenarioId,
      Scenario.Tier tier,
      String tag,
      String commit,
      Scenario.Heuristic heuristic,
      double[] weights,
      String agent) {

    static Options parse(String[] args) {
      String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "run";
      Path corpus = Path.of("stonebrick/data");
      Path scenarios = null;
      Path baselines = Path.of("stonebrick/baselines");
      String id = null;
      Scenario.Tier tier = null;
      String tag = null;
      String commit = "unknown";
      Scenario.Heuristic heuristic = null;
      double[] weights = null;
      String agent = null;

      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--corpus" -> corpus = Path.of(args[++i]);
          case "--scenarios" -> scenarios = Path.of(args[++i]);
          case "--baselines" -> baselines = Path.of(args[++i]);
          case "--scenario" -> id = args[++i];
          case "--tier" -> tier = Scenario.Tier.valueOf(args[++i].toUpperCase(Locale.ROOT));
          case "--tag" -> tag = args[++i];
          case "--commit" -> commit = args[++i];
          case "--heuristic" ->
              heuristic = Scenario.Heuristic.valueOf(args[++i].toUpperCase(Locale.ROOT));
          case "--weight", "--weights" -> weights = weights(args[++i]);
          case "--agent", "--loadout" -> agent = args[++i];
          default -> {
            // positional command, already taken
          }
        }
      }
      // Scenarios live in the corpus beside the captures directory, so one --corpus is enough.
      return new Options(
          command,
          corpus,
          scenarios == null ? corpus.resolve(Scenario.FILE_NAME) : scenarios,
          baselines,
          id,
          tier,
          tag,
          commit,
          heuristic,
          weights,
          agent);
    }

    private static double[] weights(String value) {
      String[] parts = value.split(",");
      double[] parsed = new double[parts.length];
      for (int i = 0; i < parts.length; i++) {
        parsed[i] = Double.parseDouble(parts[i].strip());
      }
      return parsed;
    }
  }

  /** Logs nothing: the search's own debug output would bury the report. */
  private static final class QuietLogger extends CobblestoneLogger {
    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {}

    @Override
    public void error(String message, Throwable throwable, Object... args) {
      System.err.println("error: " + message);
    }
  }
}
