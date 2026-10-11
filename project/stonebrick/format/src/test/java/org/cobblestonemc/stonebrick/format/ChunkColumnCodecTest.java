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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ChunkColumnCodecTest {

  private static final String AIR = Sbc.AIR;
  private static final String STONE = "minecraft:stone";
  private static final String WATER = "minecraft:water[level=0]";
  private static final String DOOR = "minecraft:oak_door[facing=east,half=lower,open=false]";

  private static String[] filled(String state) {
    String[] states = new String[Sbc.CUBE_VOLUME];
    java.util.Arrays.fill(states, state);
    return states;
  }

  @Test
  void uniformCubeRoundTrips() throws Exception {
    ChunkColumn column =
        ChunkColumn.builder(3, -7, -4)
            .cubeAtSection(0, filled(STONE))
            .cubeAtSection(5, filled(AIR))
            .build();

    ChunkColumn decoded = ChunkColumnCodec.read(ChunkColumnCodec.write(column));

    assertEquals(3, decoded.chunkX());
    assertEquals(-7, decoded.chunkZ());
    assertEquals(-4, decoded.minSectionY());
    assertEquals(2, decoded.cubeCount());
    assertEquals(STONE, decoded.blockStateOrNull(0, 0, 0));
    assertEquals(STONE, decoded.blockStateOrNull(15, 15, 15));
    assertEquals(AIR, decoded.blockStateOrNull(15, 80, 15));
    assertTrue(decoded.cubeAt(0).isUniform());
    // Between the two captured sections there is nothing at all.
    assertNull(decoded.cubeAt(40));
  }

  @Test
  void packedCubeRoundTripsEveryBlock() throws Exception {
    String[] states = new String[Sbc.CUBE_VOLUME];
    Random random = new Random(20260922L);
    String[] choices = {AIR, STONE, WATER, DOOR};
    for (int i = 0; i < states.length; i++) {
      states[i] = choices[random.nextInt(choices.length)];
    }
    ChunkColumn column = ChunkColumn.builder(0, 0, -4).cubeAtSection(4, states).build();

    ChunkColumn decoded = ChunkColumnCodec.read(ChunkColumnCodec.write(column));

    // Every single block, not a sample: a bit-packing bug shows up at one offset in four thousand.
    for (int y = 0; y < Sbc.CUBE_SIZE; y++) {
      for (int z = 0; z < Sbc.CUBE_SIZE; z++) {
        for (int x = 0; x < Sbc.CUBE_SIZE; x++) {
          assertEquals(
              states[Sbc.blockIndex(x, y, z)],
              decoded.blockStateOrNull(x, Sbc.sectionFloor(4) + y, z),
              "block at " + x + "," + y + "," + z);
        }
      }
    }
  }

  @Test
  void everyPaletteWidthRoundTrips() throws Exception {
    // Widths 1..12 exercise each entries-per-long value, including the ones that leave a partial
    // trailing long.
    for (int distinct = 2; distinct <= 4096; distinct *= 2) {
      String[] states = new String[Sbc.CUBE_VOLUME];
      for (int i = 0; i < states.length; i++) {
        states[i] = i % distinct == 0 ? AIR : "minecraft:test_" + (i % distinct);
      }
      ChunkColumn column = ChunkColumn.builder(1, 1, 0).cubeAtSection(0, states).build();

      ChunkColumn decoded = ChunkColumnCodec.read(ChunkColumnCodec.write(column));

      for (int i = 0; i < states.length; i++) {
        int x = i & 15;
        int z = (i >> 4) & 15;
        int y = i >> 8;
        assertEquals(
            states[i], decoded.blockStateOrNull(x, y, z), "distinct=" + distinct + " index=" + i);
      }
    }
  }

  @Test
  void absentCubesAreDistinctFromAir() throws Exception {
    ChunkColumn column = ChunkColumn.builder(0, 0, -4).cubeAtSection(4, filled(STONE)).build();

    ChunkColumn decoded = ChunkColumnCodec.read(ChunkColumnCodec.write(column));

    assertEquals(STONE, decoded.blockStateOrNull(0, 64, 0));
    assertNotNull(decoded.cubeAt(64));
    // One cube below was never captured. That is not air, and the platform must be able to tell.
    assertNull(decoded.cubeAt(48));
    assertNull(decoded.blockStateOrNull(0, 48, 0));
    assertEquals(ChunkColumn.ABSENT, decoded.paletteIndexOrAbsent(0, 48, 0));
  }

  @Test
  void writingIsBytewiseDeterministic() throws Exception {
    // The property the whole format exists for: re-capturing unchanged terrain must produce
    // identical bytes, or every re-capture churns version control.
    byte[] first = ChunkColumnCodec.write(buildMixedColumn());
    byte[] second = ChunkColumnCodec.write(buildMixedColumn());
    assertArrayEquals(first, second);
  }

  @Test
  void uniformCubesAreNearlyFree() throws Exception {
    // The reason a full-column capture costs far less than its volume: below the surface almost
    // every cube is solid stone.
    ChunkColumn.Builder builder = ChunkColumn.builder(0, 0, -4);
    for (int section = -4; section < 20; section++) {
      builder.cubeAtSection(section, filled(STONE));
    }
    byte[] encoded = ChunkColumnCodec.write(builder.build());
    assertTrue(encoded.length < 200, "24 uniform cubes took " + encoded.length + " bytes");
  }

  @Test
  void corruptionIsRejected() throws Exception {
    byte[] encoded = ChunkColumnCodec.write(buildMixedColumn());
    encoded[encoded.length / 2] ^= 0x40;

    CaptureFormatException thrown =
        assertThrows(CaptureFormatException.class, () -> ChunkColumnCodec.read(encoded));
    assertTrue(thrown.getMessage().contains("checksum"), thrown.getMessage());
  }

  @Test
  void foreignBytesAreRejected() {
    byte[] notACapture =
        "this is not a capture file at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThrows(CaptureFormatException.class, () -> ChunkColumnCodec.read(notACapture));
  }

  @Test
  void paletteMustStartWithAir() {
    assertThrows(CaptureFormatException.class, () -> BlockPalette.of(java.util.List.of(STONE)));
  }

  @Test
  void identicalWriteToDiskIsSkipped(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
      throws Exception {
    java.nio.file.Path file = dir.resolve("c.0.0.sbc");
    assertTrue(ChunkColumnCodec.write(buildMixedColumn(), file));
    // Re-capturing unchanged terrain must not touch the file, or `git status` is never clean.
    assertFalse(ChunkColumnCodec.write(buildMixedColumn(), file));
    assertEquals(buildMixedColumn().cubeCount(), ChunkColumnCodec.read(file).cubeCount());
  }

  private static ChunkColumn buildMixedColumn() {
    String[] mixed = filled(STONE);
    for (int i = 0; i < mixed.length; i += 3) {
      mixed[i] = AIR;
    }
    for (int i = 7; i < mixed.length; i += 29) {
      mixed[i] = WATER;
    }
    return ChunkColumn.builder(12, -34, -4)
        .cubeAtSection(-4, filled(STONE))
        .cubeAtSection(3, mixed)
        .cubeAtSection(4, filled(AIR))
        .build();
  }
}
