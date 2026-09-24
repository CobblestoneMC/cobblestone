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
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.SearchObserver;

/**
 * The benchmark command line.
 *
 * <pre>
 *   bench run      [--scenario id] [--tier ci|local] [--tag t]   run and compare against baselines
 *   bench accept   [--scenario id] [--tier ci|local]             run and record the results as accepted
 *   bench list                                                   show the corpus
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

    if (scenarios.isEmpty()) {
      System.out.println(
          "No scenarios matched. Looked in " + options.scenariosDir().toAbsolutePath());
      System.exit(options.command().equals("list") ? 0 : 2);
      return;
    }

    switch (options.command()) {
      case "list" -> list(scenarios);
      case "accept" -> accept(scenarios, options);
      case "run" -> System.exit(run(scenarios, options) ? 0 : 1);
      default -> {
        System.out.println("Unknown command '" + options.command() + "'. Try run, accept or list.");
        System.exit(2);
      }
    }
  }

  private static void list(List<Scenario> scenarios) {
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
    System.out.println("\n" + scenarios.size() + " scenarios.");
  }

  private static boolean run(List<Scenario> scenarios, Options options) throws IOException {
    ScenarioRunner runner = new ScenarioRunner(options.corpusRoot(), new QuietLogger());
    List<Comparison.Verdict> verdicts = new ArrayList<>();
    for (Scenario scenario : scenarios) {
      // ASCII: a Windows console defaults to a code page that renders an ellipsis as a
      // replacement character, and the first thing a benchmark prints should not look broken.
      System.out.println("running " + scenario.id() + "...");
      RunResult result = runner.run(scenario, SearchObserver.none());
      verdicts.add(
          Comparison.compare(result, Baselines.read(options.baselinesDir(), scenario.id())));
    }
    System.out.println();
    System.out.print(Comparison.table(verdicts));
    return verdicts.stream().allMatch(Comparison.Verdict::ok);
  }

  private static void accept(List<Scenario> scenarios, Options options) throws IOException {
    ScenarioRunner runner = new ScenarioRunner(options.corpusRoot(), new QuietLogger());
    for (Scenario scenario : scenarios) {
      RunResult result = runner.run(scenario, SearchObserver.none());
      Baselines.accept(options.baselinesDir(), result, options.commit());
      System.out.printf(
          Locale.ROOT,
          "accepted %-28s %s, %,d expanded, cost %.2f%n",
          scenario.id(),
          result.outcome(),
          result.nodesExpanded(),
          result.pathCost());
    }
    System.out.println(
        "\nReview the baseline diff before committing: it is the record of a decision.");
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
  private record Options(
      String command,
      Path corpusRoot,
      Path scenariosDir,
      Path baselinesDir,
      String scenarioId,
      Scenario.Tier tier,
      String tag,
      String commit) {

    static Options parse(String[] args) {
      String command = args.length > 0 && !args[0].startsWith("--") ? args[0] : "run";
      Path corpus = Path.of("stonebrick/data");
      Path scenarios = null;
      Path baselines = Path.of("stonebrick/baselines");
      String id = null;
      Scenario.Tier tier = null;
      String tag = null;
      String commit = "unknown";

      for (int i = 0; i < args.length; i++) {
        switch (args[i]) {
          case "--corpus" -> corpus = Path.of(args[++i]);
          case "--scenarios" -> scenarios = Path.of(args[++i]);
          case "--baselines" -> baselines = Path.of(args[++i]);
          case "--scenario" -> id = args[++i];
          case "--tier" -> tier = Scenario.Tier.valueOf(args[++i].toUpperCase(Locale.ROOT));
          case "--tag" -> tag = args[++i];
          case "--commit" -> commit = args[++i];
          default -> {
            // positional command, already taken
          }
        }
      }
      // Scenarios live in the corpus beside the captures directory, so one --corpus is enough.
      return new Options(
          command,
          corpus,
          scenarios == null
              ? org.cobblestonemc.stonebrick.format.CorpusLayout.at(corpus).scenarios()
              : scenarios,
          baselines,
          id,
          tier,
          tag,
          commit);
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
