/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.SearchObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchPipelineTest {

  private static final class SilentLogger extends CobblestoneLogger {
    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {}

    @Override
    public void error(String message, Throwable throwable, Object... args) {}
  }

  /** Writes a capture and a scenario over it, returning the corpus root. */
  private static Path corpus(Path dir, String scenarioId, int destX, int destZ, String io)
      throws IOException {
    Path root = dir.resolve("corpus");
    BenchTestCapture.flatGround(root.resolve("captures").resolve("flat"), 63, 0, 0, 4, 4);
    Path scenarios = root.resolve("scenarios");
    Files.createDirectories(scenarios);
    Files.writeString(
        scenarios.resolve(scenarioId + ".yml"),
        """
        id: %s
        description: flat ground, corner to corner
        tier: CI
        capture: flat
        tags: [smoke]
        io: %s
        origin: { world: minecraft:overworld, x: 8, y: 64, z: 8 }
        destination: { world: minecraft:overworld, x: %d, y: 64, z: %d }
        settings: { maxWallClockMillis: 60000 }
        """
            .formatted(scenarioId, io, destX, destZ));
    return root;
  }

  @Test
  void aScenarioLoadsRunsAndProducesUsableNumbers(@TempDir Path dir) throws Exception {
    Path root = corpus(dir, "flat-walk", 56, 56, "zero");

    List<Scenario> scenarios = Scenario.loadAll(root.resolve("scenarios"));
    assertEquals(1, scenarios.size());
    Scenario scenario = scenarios.get(0);
    assertEquals("flat-walk", scenario.id());
    assertEquals(Scenario.Tier.CI, scenario.tier());
    assertTrue(scenario.tags().contains("smoke"));

    RunResult result =
        new ScenarioRunner(root, new SilentLogger()).run(scenario, SearchObserver.none());

    assertEquals("success", result.outcome());
    assertTrue(result.pathCost() > 0);
    assertTrue(result.nodesExpanded() > 0);
    assertTrue(result.chunkReads() > 0);
    // Nothing was read outside the capture, so the numbers describe the world we meant.
    assertNull(result.missingCaptureReport());
    assertTrue(result.isValid());
  }

  @Test
  void twoRunsOfOneScenarioAgreeExactly(@TempDir Path dir) throws Exception {
    Path root = corpus(dir, "flat-walk", 56, 56, "spinning");
    Scenario scenario = Scenario.loadAll(root.resolve("scenarios")).get(0);
    ScenarioRunner runner = new ScenarioRunner(root, new SilentLogger());

    RunResult first = runner.run(scenario, SearchObserver.none());
    RunResult second = runner.run(scenario, SearchObserver.none());

    // Every gated metric, not a sample: this is the property a committed baseline depends on.
    assertEquals(first.deterministic(), second.deterministic());
  }

  @Test
  void anAcceptedRunThenMatchesItsBaseline(@TempDir Path dir) throws Exception {
    Path root = corpus(dir, "flat-walk", 56, 56, "nvme");
    Path baselines = dir.resolve("baselines");
    Scenario scenario = Scenario.loadAll(root.resolve("scenarios")).get(0);
    ScenarioRunner runner = new ScenarioRunner(root, new SilentLogger());

    Baselines.accept(baselines, runner.run(scenario, SearchObserver.none()), "testsha");
    Comparison.Verdict verdict =
        Comparison.compare(
            runner.run(scenario, SearchObserver.none()), Baselines.read(baselines, scenario.id()));

    assertEquals(Comparison.Status.MATCH, verdict.status());
    assertTrue(verdict.ok());
  }

  @Test
  void aChangedNumberFailsAndSaysWhatMoved(@TempDir Path dir) throws Exception {
    Path root = corpus(dir, "flat-walk", 56, 56, "zero");
    Path baselines = dir.resolve("baselines");
    Scenario scenario = Scenario.loadAll(root.resolve("scenarios")).get(0);
    RunResult real =
        new ScenarioRunner(root, new SilentLogger()).run(scenario, SearchObserver.none());
    Baselines.accept(baselines, real, "testsha");

    // Stand in for an algorithm change: the same scenario, twice the expansions.
    RunResult changed =
        new RunResult(
            real.scenario(),
            real.outcome(),
            real.pathCost(),
            real.pathTime(),
            real.pathSteps(),
            real.nodesOpened(),
            real.nodesExpanded() * 2,
            real.chunkReads(),
            real.coldChunkReads(),
            real.virtualMillis(),
            real.ioDelayMicros(),
            real.realMillis(),
            real.peakHeapBytes(),
            null);

    Comparison.Verdict verdict =
        Comparison.compare(changed, Baselines.read(baselines, scenario.id()));

    assertEquals(Comparison.Status.CHANGED, verdict.status());
    assertFalse(verdict.ok());
    String table = Comparison.table(List.of(verdict));
    assertTrue(table.contains("nodesExpanded"), table);
    assertTrue(table.contains("+100.0%"), table);
    // The table has to say what to do about it, not just that something happened.
    assertTrue(table.contains("benchAccept"), table);
  }

  @Test
  void wallClockTimeNeverFailsARun(@TempDir Path dir) throws Exception {
    Path root = corpus(dir, "flat-walk", 56, 56, "zero");
    Path baselines = dir.resolve("baselines");
    Scenario scenario = Scenario.loadAll(root.resolve("scenarios")).get(0);
    RunResult real =
        new ScenarioRunner(root, new SilentLogger()).run(scenario, SearchObserver.none());
    Baselines.accept(baselines, real, "testsha");

    // A CI runner that happened to be fifty times slower must not turn that into a red build.
    RunResult slower =
        new RunResult(
            real.scenario(),
            real.outcome(),
            real.pathCost(),
            real.pathTime(),
            real.pathSteps(),
            real.nodesOpened(),
            real.nodesExpanded(),
            real.chunkReads(),
            real.coldChunkReads(),
            real.virtualMillis(),
            real.ioDelayMicros(),
            real.realMillis() * 50 + 1000,
            real.peakHeapBytes() * 3,
            null);

    assertEquals(
        Comparison.Status.MATCH,
        Comparison.compare(slower, Baselines.read(baselines, scenario.id())).status());
  }

  @Test
  void aRunThatLeavesItsCaptureIsRejectedNotCompared(@TempDir Path dir) throws Exception {
    // Destination far outside a capture that is only five chunks square.
    Path root = corpus(dir, "runs-off", 500, 500, "zero");
    Scenario scenario = Scenario.loadAll(root.resolve("scenarios")).get(0);

    RunResult result =
        new ScenarioRunner(root, new SilentLogger()).run(scenario, SearchObserver.none());

    assertFalse(result.isValid());
    assertNotNull(result.missingCaptureReport());
    Comparison.Verdict verdict = Comparison.compare(result, null);
    assertEquals(Comparison.Status.DEGENERATE, verdict.status());
    assertFalse(verdict.ok());
    // A degenerate run must never become a baseline, or the bad world is what we compare to
    // forever.
    assertTrue(
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> Baselines.accept(dir.resolve("baselines"), result, "sha"))
            .getMessage()
            .contains("outside its capture"));
  }

  @Test
  void aCaptureMayBeNamedScenarios(@TempDir Path dir) throws Exception {
    // The two live in separate directories, so the name carries no special meaning. Reserving it
    // would have been a patch on an ambiguity that no longer exists.
    Path root = dir.resolve("corpus");
    BenchTestCapture.flatGround(root.resolve("captures").resolve("scenarios"), 63, 0, 0, 4, 4);
    Files.createDirectories(root.resolve("scenarios"));
    Files.writeString(
        root.resolve("scenarios").resolve("odd.yml"),
        """
        id: odd
        capture: scenarios
        origin:      { world: minecraft:overworld, x: 8, y: 64, z: 8 }
        destination: { world: minecraft:overworld, x: 24, y: 64, z: 24 }
        """);

    Scenario scenario = Scenario.loadAll(root.resolve("scenarios")).get(0);
    RunResult result =
        new ScenarioRunner(root, new SilentLogger()).run(scenario, SearchObserver.none());

    assertEquals("success", result.outcome());
  }

  @Test
  void aMissingBaselineIsReportedAsNewRatherThanAFailure(@TempDir Path dir) throws Exception {
    Path root = corpus(dir, "flat-walk", 56, 56, "zero");
    Scenario scenario = Scenario.loadAll(root.resolve("scenarios")).get(0);
    RunResult result =
        new ScenarioRunner(root, new SilentLogger()).run(scenario, SearchObserver.none());

    Comparison.Verdict verdict = Comparison.compare(result, null);

    assertEquals(Comparison.Status.NEW, verdict.status());
    assertTrue(verdict.ok(), "a scenario with no baseline yet should not fail the build");
  }
}
