/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraitTableTest {

  private static final String WATER = "minecraft:water[level=0]";

  private static BlockTraits waterTraits() {
    return BlockTraits.builder()
        .set(BlockTraits.WATER, true)
        .set(BlockTraits.SUPPORTS_BOAT, true)
        .set(BlockTraits.PASSABLE, false)
        .enterable(0, true)
        .exitable(5, true)
        .breakTimeSeconds(Double.POSITIVE_INFINITY)
        .speedFactor(0.5)
        .damagePerSecond(0.0)
        .build();
  }

  @Test
  void traitsRoundTripThroughTheFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(TraitTable.FILE_NAME);
    TraitTable original =
        TraitTable.of(
            Map.of(
                Sbc.AIR,
                BlockTraits.builder().set(BlockTraits.PASSABLE, true).build(),
                WATER,
                waterTraits()));
    assertTrue(original.write(file));

    TraitTable read = TraitTable.read(file);

    assertEquals(2, read.size());
    BlockTraits water = read.get(WATER);
    assertTrue(water.has(BlockTraits.WATER));
    assertTrue(water.has(BlockTraits.SUPPORTS_BOAT));
    assertFalse(water.has(BlockTraits.PASSABLE));
    assertTrue(water.enterableFrom(0));
    assertFalse(water.enterableFrom(1));
    assertTrue(water.exitableToward(5));
    assertEquals(Double.POSITIVE_INFINITY, water.breakTimeSeconds());
    assertEquals(0.5, water.speedFactor());
  }

  @Test
  void rewritingUnchangedTraitsDoesNotTouchTheFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(TraitTable.FILE_NAME);
    TraitTable table = TraitTable.of(Map.of(Sbc.AIR, waterTraits()));
    assertTrue(table.write(file));
    // Regenerating a trait table that has not changed must not produce a version-control diff.
    assertFalse(table.write(file));
  }

  @Test
  void entriesAreSortedSoRegenerationDiffsCleanly(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(TraitTable.FILE_NAME);
    TraitTable.of(
            Map.of(
                "minecraft:zzz",
                waterTraits(),
                Sbc.AIR,
                waterTraits(),
                "minecraft:mmm",
                waterTraits()))
        .write(file);

    List<String> states =
        Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .filter(line -> !line.startsWith("#"))
            .map(line -> line.substring(0, line.indexOf('\t')))
            .toList();

    assertEquals(List.of(Sbc.AIR, "minecraft:mmm", "minecraft:zzz"), states);
  }

  @Test
  void resolvingAPaletteFailsLoudlyOnAStaleTable() throws Exception {
    BlockPalette palette = BlockPalette.of(List.of(Sbc.AIR, WATER));
    TraitTable stale = TraitTable.of(Map.of(Sbc.AIR, waterTraits()));

    CaptureFormatException thrown =
        assertThrows(CaptureFormatException.class, () -> stale.resolve(palette));

    // The message has to name the state and say what to do; this fires long after the capture.
    assertTrue(thrown.getMessage().contains(WATER), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("/copier traits"), thrown.getMessage());
  }

  @Test
  void resolvingAPaletteGivesTraitsInIndexOrder() throws Exception {
    BlockPalette palette = BlockPalette.of(List.of(Sbc.AIR, WATER));
    BlockTraits air = BlockTraits.builder().set(BlockTraits.PASSABLE, true).build();
    TraitTable table = TraitTable.of(Map.of(Sbc.AIR, air, WATER, waterTraits()));

    assertArrayEquals(new BlockTraits[] {air, waterTraits()}, table.resolve(palette));
  }

  @Test
  void unknownStatesReadAsNullRatherThanADefault() {
    assertNull(TraitTable.of(Map.of()).get("minecraft:stone"));
  }

  @Test
  void aForeignFileIsRejected(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("not-traits.tsv");
    Files.writeString(file, "state\tbits\n");
    assertThrows(CaptureFormatException.class, () -> TraitTable.read(file));
  }
}
