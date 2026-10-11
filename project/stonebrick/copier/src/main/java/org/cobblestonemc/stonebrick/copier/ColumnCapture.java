/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;
import org.cobblestonemc.stonebrick.format.BlockTraits;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.Sbc;
import org.cobblestonemc.stonebrick.format.TraitTable;

/**
 * Turns one chunk snapshot into a capturable column, collecting the traits of every block state it
 * contains along the way.
 *
 * <p>Runs off the server thread: a {@link ChunkSnapshot} is an immutable copy, so the only reason
 * this would need a tick is to ask the server for the snapshot in the first place.
 */
final class ColumnCapture {

  private ColumnCapture() {}

  /**
   * Captures the requested part of a chunk.
   *
   * @param snapshot the chunk snapshot
   * @param worldMinY the world's lowest block Y
   * @param worldMaxY the world's highest block Y
   * @param mode how much of the column to capture
   * @param traits collects the traits of every block state encountered
   * @return the column
   */
  static ChunkColumn capture(
      ChunkSnapshot snapshot,
      int worldMinY,
      int worldMaxY,
      VerticalMode mode,
      TraitTable.Builder traits) {
    int minSection = Sbc.sectionOf(worldMinY);
    int maxSection = Sbc.sectionOf(worldMaxY);
    int[] range = mode.sections(surfaceY(snapshot, worldMinY), minSection, maxSection);

    ChunkColumn.Builder column = ChunkColumn.builder(snapshot.getX(), snapshot.getZ(), minSection);
    // Block state strings are interned per column: a chunk holds thousands of blocks and a few
    // dozen distinct states, and `getAsString` allocates a fresh string every call.
    Map<BlockData, String> names = new HashMap<>();
    String[] states = new String[Sbc.CUBE_VOLUME];

    for (int section = range[0]; section <= range[1]; section++) {
      // An empty section is all air, and the server can say so without us reading 4 096 blocks.
      if (snapshot.isSectionEmpty(section - minSection)) {
        java.util.Arrays.fill(states, Sbc.AIR);
        traits.add(Sbc.AIR, AIR_TRAITS.get());
        column.cubeAtSection(section, states);
        continue;
      }
      int floor = Sbc.sectionFloor(section);
      for (int y = 0; y < Sbc.CUBE_SIZE; y++) {
        for (int z = 0; z < Sbc.CUBE_SIZE; z++) {
          for (int x = 0; x < Sbc.CUBE_SIZE; x++) {
            BlockData data = snapshot.getBlockData(x, floor + y, z);
            String state = names.computeIfAbsent(data, BlockData::getAsString);
            if (!traits.contains(state)) {
              traits.add(state, TraitCapture.of(data));
            }
            states[Sbc.blockIndex(x, y, z)] = state;
          }
        }
      }
      column.cubeAtSection(section, states);
    }
    return column.build();
  }

  /**
   * Returns the highest non-air Y anywhere in the chunk, or the world floor if the chunk is empty.
   *
   * <p>The <em>highest</em> rather than an average or a centre sample: a surface-following band has
   * to contain the whole chunk's terrain, and a chunk straddling a cliff would otherwise capture
   * the low side and cut the high side off mid-wall.
   */
  private static int surfaceY(ChunkSnapshot snapshot, int worldMinY) {
    int highest = worldMinY;
    for (int z = 0; z < Sbc.CUBE_SIZE; z++) {
      for (int x = 0; x < Sbc.CUBE_SIZE; x++) {
        highest = Math.max(highest, snapshot.getHighestBlockYAt(x, z));
      }
    }
    return highest;
  }

  /**
   * Air's traits, read once from a real block rather than assumed.
   *
   * <p>Lazy because {@code createBlockData} needs a running server, and this class is loaded before
   * one is guaranteed to exist.
   */
  private static final java.util.function.Supplier<BlockTraits> AIR_TRAITS =
      new java.util.function.Supplier<>() {
        private BlockTraits value;

        @Override
        public synchronized BlockTraits get() {
          if (value == null) {
            value = TraitCapture.of(org.bukkit.Material.AIR.createBlockData());
          }
          return value;
        }
      };
}
