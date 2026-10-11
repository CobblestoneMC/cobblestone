/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureDirectoryTest {

  @Test
  void worldKeysBecomeNestedDirectories(@TempDir Path dir) {
    CaptureDirectory capture = CaptureDirectory.at(dir);
    assertEquals(
        dir.resolve("minecraft").resolve("the_nether"), capture.world("minecraft:the_nether"));
    assertTrue(capture.column("minecraft:overworld", 3, -7).toString().endsWith("c.3.-7.sbc"));
  }

  @Test
  void keysWithDotsAndSlashesSurviveTheRoundTrip(@TempDir Path dir) throws Exception {
    // A namespaced key may contain dots in both halves and slashes in its path, so flattening one
    // into a single directory name would make 'a.b:c.d' and 'a:b.c.d' collide.
    CaptureDirectory capture = CaptureDirectory.at(dir);
    List<String> keys = List.of("a.b:c.d", "a:b.c.d", "myplugin:worlds/nether_expansion");
    String[] states = new String[Sbc.CUBE_VOLUME];
    java.util.Arrays.fill(states, Sbc.AIR);
    for (String key : keys) {
      ChunkColumnCodec.write(
          ChunkColumn.builder(0, 0, -4).cubeAtSection(0, states).build(),
          capture.column(key, 0, 0));
    }

    assertEquals(keys, capture.worlds());
    assertEquals(3, capture.countColumns());
  }

  @Test
  void columnNamesRoundTripThroughNegativeCoordinates() throws Exception {
    // The parser splits on the coordinate separator rather than on every dot, so a leading minus
    // must not be mistaken for it. Half the world has negative coordinates.
    assertArrayEquals(new int[] {3, -7}, CaptureDirectory.parseColumnName("c.3.-7.sbc"));
    assertArrayEquals(new int[] {-12, -34}, CaptureDirectory.parseColumnName("c.-12.-34.sbc"));
    assertArrayEquals(new int[] {-1, 0}, CaptureDirectory.parseColumnName("c.-1.0.sbc"));
    assertArrayEquals(new int[] {0, 0}, CaptureDirectory.parseColumnName("c.0.0.sbc"));
  }

  @Test
  void columnNamesMatchWhatTheDirectoryWrites(@TempDir Path dir) throws Exception {
    CaptureDirectory capture = CaptureDirectory.at(dir);
    for (int[] coordinates : List.of(new int[] {5, 9}, new int[] {-5, 9}, new int[] {-5, -9})) {
      String name =
          capture
              .column("minecraft:overworld", coordinates[0], coordinates[1])
              .getFileName()
              .toString();
      assertArrayEquals(coordinates, CaptureDirectory.parseColumnName(name), name);
    }
  }

  @Test
  void foreignFileNamesAreRejected() {
    assertThrows(CaptureFormatException.class, () -> CaptureDirectory.parseColumnName("README.md"));
    assertThrows(CaptureFormatException.class, () -> CaptureDirectory.parseColumnName("c.5.sbc"));
    assertThrows(CaptureFormatException.class, () -> CaptureDirectory.parseColumnName("c.a.b.sbc"));
  }

  @Test
  void columnsAreListedAndCounted(@TempDir Path dir) throws Exception {
    CaptureDirectory capture = CaptureDirectory.at(dir);
    String[] states = new String[Sbc.CUBE_VOLUME];
    java.util.Arrays.fill(states, Sbc.AIR);
    for (int x = 0; x < 3; x++) {
      ChunkColumn column = ChunkColumn.builder(x, -1, -4).cubeAtSection(0, states).build();
      ChunkColumnCodec.write(column, capture.column("minecraft:overworld", x, -1));
    }
    ChunkColumnCodec.write(
        ChunkColumn.builder(0, 0, -4).cubeAtSection(0, states).build(),
        capture.column("minecraft:the_nether", 0, 0));

    assertEquals(3, capture.listColumns("minecraft:overworld").size());
    assertEquals(4, capture.countColumns());
    assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), capture.worlds());
  }

  @Test
  void metadataRoundTrips(@TempDir Path dir) throws Exception {
    CaptureDirectory capture = CaptureDirectory.at(dir);
    capture.writeMeta(
        Map.of("minecraftVersion", "1.21.4", "world.minecraft:overworld.minY", "-64"));

    Map<String, String> read = capture.readMeta();

    assertEquals("1.21.4", read.get("minecraftVersion"));
    assertEquals("-64", read.get("world.minecraft:overworld.minY"));
  }
}
