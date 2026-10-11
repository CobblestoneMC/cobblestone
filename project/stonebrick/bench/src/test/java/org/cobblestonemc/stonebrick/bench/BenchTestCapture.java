/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

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
 * Writes a capture of flat ground, so the bench pipeline can be tested without a server.
 *
 * <p>Traits are hand-written here, which a production capture must never do — but the point is to
 * exercise the runner, the baselines and the comparison against terrain whose answer is known.
 */
final class BenchTestCapture {

  private static final String STONE = "minecraft:stone";
  private static final String WORLD = "minecraft:overworld";

  private BenchTestCapture() {}

  /** Writes a capture of flat stone at {@code groundY} over the given chunk rectangle. */
  static void flatGround(Path root, int groundY, int minX, int minZ, int maxX, int maxZ)
      throws IOException {
    CaptureDirectory capture = CaptureDirectory.at(root);
    int minSection = Sbc.sectionOf(groundY) - 1;
    int maxSection = Sbc.sectionOf(groundY) + 1;

    for (int chunkX = minX; chunkX <= maxX; chunkX++) {
      for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
        ChunkColumn.Builder column = ChunkColumn.builder(chunkX, chunkZ, -4);
        String[] states = new String[Sbc.CUBE_VOLUME];
        for (int section = minSection; section <= maxSection; section++) {
          int floor = Sbc.sectionFloor(section);
          for (int i = 0; i < states.length; i++) {
            states[i] = floor + (i >> 8) <= groundY ? STONE : Sbc.AIR;
          }
          column.cubeAtSection(section, states);
        }
        ChunkColumnCodec.write(column.build(), capture.column(WORLD, chunkX, chunkZ));
      }
    }

    Map<String, BlockTraits> traits = new HashMap<>();
    traits.put(Sbc.AIR, faces(BlockTraits.builder().set(BlockTraits.PASSABLE, true), true).build());
    traits.put(
        STONE,
        faces(BlockTraits.builder().set(BlockTraits.SOLID_TOP, true).breakTimeSeconds(1.5), false)
            .build());
    TraitTable.of(traits).write(capture.traitTable());

    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("world." + WORLD + ".minY", "-64");
    meta.put("world." + WORLD + ".maxY", "319");
    meta.put("world." + WORLD + ".environment", "NORMAL");
    capture.writeMeta(meta);
  }

  private static BlockTraits.Builder faces(BlockTraits.Builder builder, boolean open) {
    for (int i = 0; i < BlockTraits.DIRECTIONS.length; i++) {
      builder.enterable(i, open).exitable(i, open);
    }
    return builder;
  }
}
