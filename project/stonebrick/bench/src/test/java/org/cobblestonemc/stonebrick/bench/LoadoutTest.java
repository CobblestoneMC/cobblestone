/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadoutTest {

  private static Path write(Path dir, String yaml) throws IOException {
    Path file = dir.resolve(Loadout.FILE_NAME);
    Files.writeString(file, yaml);
    return file;
  }

  @Test
  void capabilitiesAndExclusionsAreReadSeparately(@TempDir Path dir) throws Exception {
    List<Loadout> loadouts =
        Loadout.loadAll(
            write(
                dir,
                """
                walker:
                  description: on foot
                  excludedModes: [MINE, BOAT]
                flyer:
                  canFly: true
                """));

    assertEquals(List.of("walker", "flyer"), loadouts.stream().map(Loadout::name).toList());

    Loadout walker = loadouts.get(0);
    assertFalse(walker.agent().canFly());
    assertEquals(
        java.util.Set.of(MinecraftStepType.MINE, MinecraftStepType.BOAT), walker.excludedModes());

    Loadout flyer = loadouts.get(1);
    assertTrue(flyer.agent().canFly());
    assertTrue(flyer.excludedModes().isEmpty());
  }

  /**
   * A player who may not dig still walks on soul sand. Production once dropped the medium alongside
   * mining, which priced soul sand at the fallback rate for every non-digging player, and the bench
   * kept its own derivation and never saw it.
   */
  @Test
  void aPlayerWhoCannotDigStillCrossesSoulSand(@TempDir Path dir) throws Exception {
    Loadout walker = Loadout.loadAll(write(dir, "walker:\n  excludedModes: [MINE, BOAT]\n")).get(0);

    assertTrue(walker.mediums().contains(org.cobblestonemc.minecraft.lod.Medium.SOUL_SAND));
    assertFalse(walker.mediums().contains(org.cobblestonemc.minecraft.lod.Medium.MINEABLE));
  }

  @Test
  void aLoadoutWithNoBodyIsTheFullyCapableDefault(@TempDir Path dir) throws Exception {
    // `everything:` with nothing under it parses as a null value, which is a shape a hand-edited
    // file falls into easily and should not be an error.
    List<Loadout> loadouts = Loadout.loadAll(write(dir, "everything:\n"));

    assertEquals(1, loadouts.size());
    assertTrue(loadouts.get(0).excludedModes().isEmpty());
  }

  @Test
  void aMisspelledModeSaysSoRatherThanBeingIgnored(@TempDir Path dir) throws Exception {
    // Silently dropping it would run the loadout with the wrong modes and quietly invalidate every
    // number it produced.
    Path file = write(dir, "walker:\n  excludedModes: [MINEING]\n");

    IOException thrown = assertThrows(IOException.class, () -> Loadout.loadAll(file));

    assertTrue(thrown.getMessage().contains("MINEING"), thrown.getMessage());
  }

  @Test
  void aCorpusWithNoLoadoutFileStillRuns(@TempDir Path dir) throws Exception {
    List<Loadout> loadouts = Loadout.loadAll(dir.resolve("absent.yml"));

    assertEquals(List.of(Loadout.fallback()), loadouts);
  }

  @Test
  void aRunIdNamesTheRouteAndTheTraveller(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(Scenario.FILE_NAME);
    Files.writeString(
        file,
        """
        minecraft:overworld:
          deep-cave:
            capture: deep-cave
            origin: { x: 0, y: 64, z: 0 }
            destination: { x: 8, y: 64, z: 8 }
        """);

    Scenario scenario = Scenario.loadAll(file).get(0);

    assertEquals("overworld/deep-cave", scenario.id());
    assertEquals(
        "overworld/deep-cave@flyer",
        scenario.runId(new Loadout("flyer", "", Scenario.AgentSpec.plain(), java.util.Set.of())));
  }
}
