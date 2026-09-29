/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.SearchObserver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Carrying a boat has to change how an ocean is crossed.
 *
 * <p>⚠️ <b>Currently failing, and deliberately recorded rather than deleted.</b> A {@code boater}
 * produces a byte-identical path to a {@code walker} on {@code overworld/ocean} and {@code
 * overworld/along-river} — same cost, same step count, same per-layer histogram — while differing
 * on six cave scenarios. {@code BoatMode} is in the mode list for the boater and the capture marks
 * water with {@code SUPPORTS_BOAT}, so the mode exists and the terrain supports it; what never
 * happens is the {@code PLACE_BOAT} transition that gets the agent aboard. An agent started with
 * {@code inBoat} does move differently, which places the fault in boarding rather than in boating.
 *
 * <p>Worth about 60 seconds of path cost on the ocean route: swimming is 0.30 s/block against a
 * boat's 0.15 over roughly 400 blocks.
 *
 * <p>Enable this once boarding works.
 */
@Disabled("BoatMode never boards: a boater's path is identical to a walker's on water routes")
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

    List<Loadout> loadouts = Loadout.loadAll(corpus.resolve(Loadout.FILE_NAME));
    Scenario ocean =
        Scenario.loadAll(corpus.resolve(Scenario.FILE_NAME)).stream()
            .filter(scenario -> scenario.id().equals("overworld/ocean"))
            .findFirst()
            .orElseThrow();
    ScenarioRunner runner = new ScenarioRunner(corpus, new Silent());

    RunResult walker = runner.run(ocean, named(loadouts, "walker"), SearchObserver.none());
    RunResult boater = runner.run(ocean, named(loadouts, "boater"), SearchObserver.none());

    assertNotEquals(walker.pathCost(), boater.pathCost(), 1e-9);
  }

  private static Loadout named(List<Loadout> loadouts, String name) {
    return loadouts.stream()
        .filter(loadout -> loadout.name().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
