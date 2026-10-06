/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.SearchObserver;
import org.junit.jupiter.api.Test;

/**
 * Carrying a boat has to change how an ocean is crossed.
 *
 * <p>Pinned to the coarse heuristic, which is what production runs. Under the running average a
 * {@code player-with-boat}'s path on {@code overworld/ocean} is byte-identical to a {@code
 * player}'s: it never boards (#27). Boarding itself works -- Dijkstra boards, and under the coarse
 * estimate the boat crosses for 107 s against 175 on foot -- so the fault is in the running
 * average, which prices the remaining journey at the rate it has been walking and never finds the
 * detour to the water worth taking.
 *
 * <p>Needs the local corpus, so it is skipped where {@code ocean} has not been captured.
 */
class BoatBoardingTest {

  private static final class Silent extends CobblestoneLogger {
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

  @Test
  void aBoaterCrossesAnOceanDifferentlyFromASwimmer() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null
        && !Files.isRegularFile(root.resolve("stonebrick/data").resolve(Loadout.FILE_NAME))) {
      root = root.getParent();
    }
    Path corpus = root.resolve("stonebrick/data");
    assumeTrue(
        Files.isDirectory(corpus.resolve("captures").resolve("ocean")),
        "no local capture of ocean; run ./gradlew captureCorpus");

    List<Loadout> loadouts = Loadout.loadAll(corpus.resolve(Loadout.FILE_NAME));
    Scenario ocean =
        Scenario.loadAll(corpus.resolve(Scenario.FILE_NAME)).stream()
            .filter(scenario -> scenario.id().equals("overworld/ocean"))
            .findFirst()
            .orElseThrow()
            .withHeuristic(Scenario.Heuristic.COARSE);
    ScenarioRunner runner = new ScenarioRunner(corpus, new Silent());

    RunResult player = runner.run(ocean, named(loadouts, "player"), SearchObserver.none());
    RunResult boater =
        runner.run(ocean, named(loadouts, "player-with-boat"), SearchObserver.none());

    assertNotEquals(player.pathCost(), boater.pathCost(), 1e-9);
  }

  private static Loadout named(List<Loadout> loadouts, String name) {
    return loadouts.stream()
        .filter(loadout -> loadout.name().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
