/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cobblestonemc.Cell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeededChunksTest {

  private static NeededChunks capsule(int fromX, int fromZ, int toX, int toZ, int radius) {
    return new NeededChunks(
        "minecraft:overworld",
        new Cell(fromX * 16, 64, fromZ * 16),
        new Cell(toX * 16, 64, toZ * 16),
        radius);
  }

  @Test
  void aCapsuleHugsItsRouteInsteadOfBoundingIt() {
    // A long diagonal route. A circle enclosing it would have to reach half the route's length in
    // every direction; the capsule stays a corridor, which is the whole reason for the shape.
    NeededChunks corridor = capsule(0, 0, 100, 100, 3);

    assertTrue(corridor.covers(50, 50), "the middle of the route is in the corridor");
    assertTrue(corridor.covers(52, 50), "and so is three chunks to the side of it");
    assertFalse(corridor.covers(70, 30), "but not a point far off the line");
    // A circle of the same reach would cover this; the capsule must not.
    assertFalse(corridor.covers(0, 100));
  }

  @Test
  void aShortRouteIsJustACircle() {
    NeededChunks blob = capsule(10, 10, 10, 10, 4);

    assertTrue(blob.covers(10, 10));
    assertTrue(blob.covers(14, 10));
    assertTrue(blob.covers(10, 6));
    assertFalse(blob.covers(15, 10));
  }

  @Test
  void includingAChunkWidensJustEnough() {
    NeededChunks narrow = capsule(0, 0, 10, 0, 2);
    assertFalse(narrow.covers(5, 6));

    NeededChunks widened = narrow.including(5, 6);

    assertTrue(widened.covers(5, 6), "the chunk that prompted the widening must now be covered");
    assertEquals(6, widened.radiusChunks());
    // And it does not widen further than it has to.
    assertTrue(widened.radiusChunks() < 8);
  }

  @Test
  void includingSomethingAlreadyCoveredChangesNothing() {
    // Union-only growth still has to be a no-op when there is nothing new, or every run would
    // rewrite the manifest and the diff would stop meaning anything.
    NeededChunks capsule = capsule(0, 0, 10, 0, 4);
    assertSame(capsule, capsule.including(5, 1));
  }

  @Test
  void theCapsuleOnlyEverGrows() {
    NeededChunks start = capsule(0, 0, 10, 0, 2);
    NeededChunks after = start.including(5, 9).including(5, 1).including(3, 0);

    // Two of those three are already inside the widened capsule; the radius must not fall back.
    assertEquals(9, after.radiusChunks());
  }

  @Test
  void aCapsuleCountsFewerChunksThanItsBoundingBox() {
    NeededChunks corridor = capsule(0, 0, 60, 60, 3);
    int boundingBox = (60 + 6 + 1) * (60 + 6 + 1);

    assertTrue(
        corridor.chunkCount() < boundingBox / 3,
        "capsule " + corridor.chunkCount() + " vs box " + boundingBox);
  }

  @Test
  void theManifestRoundTripsAndIsSorted(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(NeededChunks.FILE_NAME);
    Map<String, NeededChunks> needed = new LinkedHashMap<>();
    needed.put("zulu", capsule(0, 0, 5, 5, 2));
    needed.put("alpha", capsule(-3, 7, 9, -1, 6));

    NeededChunks.write(file, needed);
    Map<String, NeededChunks> read = NeededChunks.read(file);

    assertEquals(needed.get("alpha"), read.get("alpha"));
    assertEquals(needed.get("zulu"), read.get("zulu"));
    // Sorted on write, so a run that widens one scenario produces a one-entry diff.
    String text = Files.readString(file);
    assertTrue(text.indexOf("alpha:") < text.indexOf("zulu:"), text);
  }

  @Test
  void rewritingUnchangedContentIsByteIdentical(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(NeededChunks.FILE_NAME);
    Map<String, NeededChunks> needed = Map.of("a", capsule(0, 0, 5, 5, 2));

    NeededChunks.write(file, needed);
    byte[] first = Files.readAllBytes(file);
    NeededChunks.write(file, NeededChunks.read(file));

    org.junit.jupiter.api.Assertions.assertArrayEquals(first, Files.readAllBytes(file));
  }
}
