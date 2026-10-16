/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MiningClass;
import org.cobblestonemc.regions.RegionColumn;
import org.cobblestonemc.regions.RegionColumnBuilder;
import org.cobblestonemc.regions.RegionGraph;
import org.junit.jupiter.api.Test;

/** Rock of every hardness fills as one, and a region is the band nearest its average. */
class MinecraftTerrainTest {

  private static final MinecraftTerrain TERRAIN = MinecraftTerrain.INSTANCE;

  private static MinecraftBlock solid(MiningClass mining) {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return false;
      }

      @Override
      public boolean isSolidTop() {
        return true;
      }

      @Override
      public double breakTimeSeconds() {
        return 1.0;
      }

      @Override
      public MiningClass miningClass() {
        return mining;
      }
    };
  }

  private static final MinecraftBlock STONE = solid(MiningClass.ROCK);
  private static final MinecraftBlock DEEPSLATE = solid(MiningClass.HARD_ROCK);
  private static final MinecraftBlock GRAVEL = solid(MiningClass.DIRT);

  /** Counts of rock cells, by class. */
  private static int[] counts(int soft, int rock, int hard) {
    int[] counts = new int[RegionClass.ALL.length];
    counts[RegionClass.SOFT_ROCK.ordinal()] = soft;
    counts[RegionClass.ROCK.ordinal()] = rock;
    counts[RegionClass.HARD_ROCK.ordinal()] = hard;
    return counts;
  }

  private static RegionClass label(int[] counts) {
    return RegionClass.ALL[TERRAIN.label(RegionClass.ROCK.ordinal(), counts)];
  }

  @Test
  void aRockRegionIsTheBandNearestItsAverageHardness() {
    assertEquals(RegionClass.ROCK, label(counts(0, 100, 0)));
    assertEquals(RegionClass.HARD_ROCK, label(counts(0, 0, 100)));
    assertEquals(RegionClass.MIXED_ROCK, label(counts(0, 50, 50)));
    assertEquals(RegionClass.ROCK, label(counts(0, 90, 10)), "a few veins do not make it mixed");
    assertEquals(RegionClass.HARD_ROCK, label(counts(0, 10, 90)));
    assertEquals(
        RegionClass.SOFT_ROCK, label(counts(70, 30, 0)), "netherrack with some blackstone");
  }

  @Test
  void rockOfEveryHardnessIsOneFamilyAndDirtIsItsOwn() {
    int rock = RegionClass.ROCK.ordinal();
    assertEquals(rock, TERRAIN.family(RegionClass.SOFT_ROCK.ordinal()));
    assertEquals(rock, TERRAIN.family(RegionClass.HARD_ROCK.ordinal()));
    assertEquals(RegionClass.DIRT.ordinal(), TERRAIN.family(RegionClass.DIRT.ordinal()));
    assertNotEquals(rock, TERRAIN.family(RegionClass.WOOD.ordinal()));
  }

  @Test
  void stoneDitheredWithDeepslateFillsAsOneRegion() {
    // One section of rock, stone and deepslate in a checkerboard, under open air: the way the
    // world blends the two between y 0 and 8.
    MinecraftChunk dithered =
        (x, y, z) -> y >= 16 ? air() : (x + y + z) % 2 == 0 ? STONE : DEEPSLATE;
    MinecraftChunk plain = (x, y, z) -> y >= 16 ? air() : STONE;
    MinecraftChunk pocket = (x, y, z) -> y >= 16 ? air() : x < 4 && z < 4 && y < 4 ? GRAVEL : STONE;

    assertEquals(regions(plain), regions(dithered), "dithering adds no regions");
    assertEquals(regions(plain) + 1, regions(pocket), "a shovel's pocket is a region of its own");
  }

  private static MinecraftBlock air() {
    return new MinecraftBlock() {
      @Override
      public boolean isPassable() {
        return true;
      }

      @Override
      public boolean isSolidTop() {
        return false;
      }
    };
  }

  private static int regions(MinecraftChunk chunk) {
    RegionColumnBuilder<MinecraftChunk> builder =
        new RegionColumnBuilder<>(TERRAIN, 0, 31, RegionGraph.DEFAULT_DETOUR_RATIO);
    RegionColumn column = builder.build(0, 0, chunk);
    return column.regions();
  }
}
