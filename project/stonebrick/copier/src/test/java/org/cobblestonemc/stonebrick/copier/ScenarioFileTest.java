/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioFileTest {

  @Test
  void aMarkCreatesAnEntryThenUpdatesIt(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(ScenarioFile.FILE_NAME);

    assertTrue(ScenarioFile.mark(file, "river", "origin", "minecraft:overworld", 1, 2, 3));
    assertFalse(ScenarioFile.mark(file, "river", "dest", "minecraft:overworld", 9, 8, 7));

    String text = Files.readString(file);
    assertTrue(text.contains("origin: { x: 1, y: 2, z: 3 }"), text);
    assertTrue(text.contains("destination: { x: 9, y: 8, z: 7 }"), text);
    assertTrue(text.contains("capture: river"), text);
  }

  @Test
  void remarkingReplacesRatherThanAppends(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(ScenarioFile.FILE_NAME);
    ScenarioFile.mark(file, "river", "origin", "minecraft:overworld", 1, 2, 3);
    ScenarioFile.mark(file, "river", "origin", "minecraft:overworld", 4, 5, 6);

    String text = Files.readString(file);
    assertFalse(text.contains("x: 1,"), text);
    assertTrue(text.contains("origin: { x: 4, y: 5, z: 6 }"), text);
    assertEquals(1, text.lines().filter(line -> line.contains("origin:")).count());
  }

  @Test
  void entriesAreAlphabeticalSoAMarkIsAOneEntryDiff(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(ScenarioFile.FILE_NAME);
    ScenarioFile.mark(file, "zulu", "origin", "minecraft:overworld", 0, 0, 0);
    ScenarioFile.mark(file, "alpha", "origin", "minecraft:overworld", 0, 0, 0);
    ScenarioFile.mark(file, "mike", "origin", "minecraft:overworld", 0, 0, 0);

    String text = Files.readString(file);
    assertTrue(text.indexOf("alpha:") < text.indexOf("mike:"), text);
    assertTrue(text.indexOf("mike:") < text.indexOf("zulu:"), text);
  }

  @Test
  void handEditedFieldsSurviveAMark(@TempDir Path dir) throws Exception {
    // The whole point of reading the file as lines: a mark must not destroy the settings, tags or
    // agent that somebody typed by hand, nor the comments explaining them.
    Path file = dir.resolve(ScenarioFile.FILE_NAME);
    Files.writeString(
        file,
        """
        river:
          capture: river
          world: minecraft:overworld
          agent: { hasBoat: true }
          settings: { maxCellsVisited: 40000 }
          origin: { x: 0, y: 0, z: 0 }
        """);

    ScenarioFile.mark(file, "river", "dest", "minecraft:overworld", 9, 8, 7);

    String text = Files.readString(file);
    assertTrue(text.contains("agent: { hasBoat: true }"), text);
    assertTrue(text.contains("settings: { maxCellsVisited: 40000 }"), text);
    assertTrue(text.contains("destination: { x: 9, y: 8, z: 7 }"), text);
  }

  @Test
  void rewritingUnchangedContentIsByteIdentical(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(ScenarioFile.FILE_NAME);
    ScenarioFile.mark(file, "river", "origin", "minecraft:overworld", 1, 2, 3);
    byte[] first = Files.readAllBytes(file);

    ScenarioFile.mark(file, "river", "origin", "minecraft:overworld", 1, 2, 3);

    assertArrayEquals(first, Files.readAllBytes(file));
  }

  @Test
  void aCapsuleCoversItsCorridorAndNotTheBoxAroundIt() {
    assertTrue(Capsule.contains(0, 0, 100, 100, 3, 50, 50));
    assertTrue(Capsule.contains(0, 0, 100, 100, 3, 52, 50));
    assertFalse(Capsule.contains(0, 0, 100, 100, 3, 0, 100));
    // Degenerate segment: a circle.
    assertTrue(Capsule.contains(5, 5, 5, 5, 2, 7, 5));
    assertFalse(Capsule.contains(5, 5, 5, 5, 2, 8, 5));
  }
}
