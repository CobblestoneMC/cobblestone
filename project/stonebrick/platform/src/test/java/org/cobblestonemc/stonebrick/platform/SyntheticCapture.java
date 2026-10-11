/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cobblestonemc.stonebrick.format.BlockTraits;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.ChunkColumnCodec;
import org.cobblestonemc.stonebrick.format.Sbc;
import org.cobblestonemc.stonebrick.format.TraitTable;

/**
 * Builds a capture on disk from a block-placing function, so tests can describe a world instead of
 * needing a server to capture one.
 *
 * <p>Traits here are written by hand rather than read from a platform, which is exactly what
 * production captures must never do — but a unit test's job is to exercise the reading path against
 * known terrain, and known terrain means terrain this file decides.
 */
final class SyntheticCapture {

  static final String STONE = "minecraft:stone";
  static final String AIR = Sbc.AIR;
  static final String WATER = "minecraft:water[level=0]";
  static final String OVERWORLD = "minecraft:overworld";

  /** Decides the block at a world position. */
  interface Terrain {
    String blockAt(int x, int y, int z);
  }

  private SyntheticCapture() {}

  /**
   * Writes a capture covering the given chunk rectangle and section range.
   *
   * @param root the capture directory to create
   * @param terrain the terrain function
   * @param minChunkX rectangle bound, inclusive
   * @param minChunkZ rectangle bound, inclusive
   * @param maxChunkX rectangle bound, inclusive
   * @param maxChunkZ rectangle bound, inclusive
   * @param minSection the lowest section to capture
   * @param maxSection the highest section to capture
   * @throws IOException if the capture cannot be written
   */
  static void write(
      Path root,
      Terrain terrain,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ,
      int minSection,
      int maxSection)
      throws IOException {
    CaptureDirectory capture = CaptureDirectory.at(root);
    int worldMinSection = -4;

    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
        ChunkColumn.Builder column = ChunkColumn.builder(chunkX, chunkZ, worldMinSection);
        String[] states = new String[Sbc.CUBE_VOLUME];
        for (int section = minSection; section <= maxSection; section++) {
          int floor = Sbc.sectionFloor(section);
          for (int y = 0; y < Sbc.CUBE_SIZE; y++) {
            for (int z = 0; z < Sbc.CUBE_SIZE; z++) {
              for (int x = 0; x < Sbc.CUBE_SIZE; x++) {
                states[Sbc.blockIndex(x, y, z)] =
                    terrain.blockAt((chunkX << 4) + x, floor + y, (chunkZ << 4) + z);
              }
            }
          }
          column.cubeAtSection(section, states);
        }
        ChunkColumnCodec.write(column.build(), capture.column(OVERWORLD, chunkX, chunkZ));
      }
    }

    traits().write(capture.traitTable());

    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("world." + OVERWORLD + ".minY", "-64");
    meta.put("world." + OVERWORLD + ".maxY", "319");
    meta.put("world." + OVERWORLD + ".environment", "NORMAL");
    meta.put("minecraftVersion", "test");
    capture.writeMeta(meta);
  }

  /** A trait table for the handful of states the synthetic worlds use. */
  static TraitTable traits() {
    Map<String, BlockTraits> traits = new HashMap<>();
    traits.put(AIR, passable());
    traits.put(WATER, water());
    traits.put(STONE, solid());
    return TraitTable.of(traits);
  }

  private static BlockTraits passable() {
    BlockTraits.Builder builder =
        BlockTraits.builder().set(BlockTraits.PASSABLE, true).breakTimeSeconds(0.0);
    return allFaces(builder, true).build();
  }

  private static BlockTraits solid() {
    BlockTraits.Builder builder =
        BlockTraits.builder()
            .set(BlockTraits.SOLID_TOP, true)
            .set(BlockTraits.PASSABLE, false)
            .breakTimeSeconds(1.5);
    return allFaces(builder, false).build();
  }

  private static BlockTraits water() {
    BlockTraits.Builder builder =
        BlockTraits.builder()
            .set(BlockTraits.WATER, true)
            .set(BlockTraits.SUPPORTS_BOAT, true)
            .set(BlockTraits.PASSABLE, false)
            .breakTimeSeconds(Double.POSITIVE_INFINITY);
    return allFaces(builder, true).build();
  }

  private static BlockTraits.Builder allFaces(BlockTraits.Builder builder, boolean value) {
    for (int i = 0; i < BlockTraits.DIRECTIONS.length; i++) {
      builder.enterable(i, value).exitable(i, value);
    }
    return builder;
  }

  /** Flat ground: stone up to {@code groundY}, air above. */
  static Terrain flatGround(int groundY) {
    return (x, y, z) -> y <= groundY ? STONE : AIR;
  }
}
