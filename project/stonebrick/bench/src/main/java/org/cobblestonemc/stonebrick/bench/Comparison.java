/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Sets a run against its accepted baseline and says what moved.
 *
 * <p><b>A difference is not a regression.</b> Halving node count for one percent more path cost is
 * very likely the trade that was wanted; the comparison cannot tell, and does not try. What it
 * guarantees is that the change cannot pass <em>silently</em>: the run fails, a human reads the
 * table, and either the change is wrong or the baseline is re-accepted as a reviewed diff.
 *
 * <p>Only the deterministic metrics decide the verdict. Wall time appears in the table because it
 * is worth seeing, and never in the verdict because on a shared CI runner it means very little.
 */
public final class Comparison {

  /** How to accept new numbers, printed where a reader needs it. */
  static final String ACCEPT =
      "./gradlew :stonebrick:stonebrick-bench:run --args=\"accept --scenario <id>\"";

  private Comparison() {}

  /** How one metric moved. */
  public record Delta(String metric, double baseline, double current, boolean gated) {

    /** Returns the fractional change, or {@code 0} when both sides are zero. */
    public double fraction() {
      if (baseline == 0.0) {
        return current == 0.0 ? 0.0 : 1.0;
      }
      return (current - baseline) / Math.abs(baseline);
    }

    /** Returns whether this movement is beyond its tolerance and therefore fails the run. */
    public boolean breached() {
      return gated && Math.abs(fraction()) > Baselines.toleranceFor(metric);
    }
  }

  /** The verdict on one scenario. */
  public record Verdict(String scenario, Status status, String detail, List<Delta> deltas) {

    /** Whether the scenario passed. */
    public boolean ok() {
      return status == Status.MATCH || status == Status.NEW;
    }
  }

  /** What happened to a scenario. */
  public enum Status {
    /** Within tolerance on every gated metric. */
    MATCH,

    /** A gated metric moved beyond tolerance. */
    CHANGED,

    /** The outcome itself differs — success became failure, or the reverse. */
    OUTCOME,

    /** The run read outside its capture, so its numbers describe nothing. */
    DEGENERATE,

    /**
     * The baseline measured something else: another heuristic, weight or IO model, or another
     * capture of the terrain. Its numbers are not compared, because every difference would be
     * reported as the algorithm's.
     */
    CONFIGURATION,

    /** No baseline yet; nothing to compare against. */
    NEW
  }

  /**
   * Compares a run to its baseline.
   *
   * @param result the run
   * @param baseline the accepted numbers, or {@code null} if there are none yet
   * @return the verdict
   */
  public static Verdict compare(RunResult result, @Nullable Baselines.Baseline baseline) {
    if (!result.isValid()) {
      // Checked before anything else: a degenerate run's numbers are not worth comparing, and
      // reporting them as a regression would send someone looking in the wrong place entirely.
      return new Verdict(
          result.scenario(), Status.DEGENERATE, result.missingCaptureReport(), List.of());
    }
    if (baseline == null) {
      return new Verdict(
          result.scenario(), Status.NEW, "no baseline yet; accept it to start tracking", List.of());
    }

    if (!baseline.configuration().equals(result.configuration())) {
      return new Verdict(
          result.scenario(),
          Status.CONFIGURATION,
          configurationDifference(baseline.configuration(), result.configuration()),
          List.of());
    }

    List<Delta> deltas = new ArrayList<>();
    for (Map.Entry<String, Number> entry : result.deterministic().entrySet()) {
      Number before = baseline.deterministic().get(entry.getKey());
      if (before != null) {
        deltas.add(
            new Delta(entry.getKey(), before.doubleValue(), entry.getValue().doubleValue(), true));
      }
    }
    for (Map.Entry<String, Number> entry : result.advisory().entrySet()) {
      Number before = baseline.advisory().get(entry.getKey());
      if (before != null) {
        deltas.add(
            new Delta(entry.getKey(), before.doubleValue(), entry.getValue().doubleValue(), false));
      }
    }

    if (!result.outcome().equals(baseline.outcome())) {
      return new Verdict(
          result.scenario(),
          Status.OUTCOME,
          baseline.outcome() + " -> " + result.outcome(),
          deltas);
    }
    boolean breached = deltas.stream().anyMatch(Delta::breached);
    return new Verdict(result.scenario(), breached ? Status.CHANGED : Status.MATCH, "", deltas);
  }

  /**
   * Renders verdicts as a table.
   *
   * @param verdicts the verdicts
   * @return the table, ready to print
   */
  public static String table(List<Verdict> verdicts) {
    StringBuilder text = new StringBuilder();
    text.append(
        String.format(
            Locale.ROOT, "%-28s %-11s %s%n", "scenario", "status", "what moved (gated metrics)"));
    text.append("-".repeat(96)).append('\n');
    for (Verdict verdict : verdicts) {
      List<Delta> notable =
          verdict.deltas().stream().filter(delta -> delta.gated() && delta.breached()).toList();
      String summary =
          notable.isEmpty()
              ? verdict.detail().lines().findFirst().orElse("")
              : notable.stream()
                  .map(Comparison::describe)
                  .reduce((a, b) -> a + "  " + b)
                  .orElse("");
      text.append(
          String.format(
              Locale.ROOT, "%-28s %-11s %s%n", verdict.scenario(), verdict.status(), summary));
      if (verdict.status() == Status.DEGENERATE) {
        // The first line is already the summary column; repeating it wastes the widest
        // line of the report on something the reader has just read.
        verdict
            .detail()
            .lines()
            .skip(1)
            .forEach(line -> text.append("    ").append(line).append(System.lineSeparator()));
      }
    }
    long failed = verdicts.stream().filter(verdict -> !verdict.ok()).count();
    text.append(System.lineSeparator());
    if (failed == 0) {
      long fresh = verdicts.stream().filter(v -> v.status() == Status.NEW).count();
      if (fresh == verdicts.size()) {
        text.append(
            "%d scenario(s) have no baseline yet. Accept them to start tracking:%n  %s%n"
                .formatted(fresh, ACCEPT.replace(" --scenario <id>", "")));
      } else if (fresh > 0) {
        text.append(
            "%d scenario(s) match their baselines; %d have none yet.%n"
                .formatted(verdicts.size() - fresh, fresh));
      } else {
        text.append("All %d scenarios match their baselines.%n".formatted(verdicts.size()));
      }
    } else {
      // Built with one formatted() call: concatenating a formatted string with a raw one leaves the
      // second half's %n sitting in the output as literal characters.
      text.append(
          """
          %d of %d scenarios differ. Review the table; if the new numbers are right, accept them:
            %s
          """
              .formatted(failed, verdicts.size(), ACCEPT));
    }
    return text.toString();
  }

  private static String configurationDifference(
      Map<String, String> baseline, Map<String, String> current) {
    if (baseline.isEmpty()) {
      return "baseline does not record what it measured; re-accept it";
    }
    List<String> moved = new ArrayList<>();
    for (Map.Entry<String, String> entry : current.entrySet()) {
      String before = baseline.get(entry.getKey());
      if (!entry.getValue().equals(before)) {
        moved.add(entry.getKey() + " " + before + " -> " + entry.getValue());
      }
    }
    return "measured differently: " + String.join("; ", moved);
  }

  private static String describe(Delta delta) {
    return "%s %s -> %s (%+.1f%%)"
        .formatted(
            delta.metric(),
            number(delta.baseline()),
            number(delta.current()),
            delta.fraction() * 100);
  }

  private static String number(double value) {
    return value == Math.rint(value) && Math.abs(value) < 1e15
        ? String.valueOf((long) value)
        : String.format(Locale.ROOT, "%.3f", value);
  }
}
