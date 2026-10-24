/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld.Environment;
import org.cobblestonemc.minecraft.MiningClass;
import org.cobblestonemc.regions.RegionColumn;
import org.cobblestonemc.regions.RegionColumnBuilder;
import org.cobblestonemc.regions.RegionSpec;
import org.junit.jupiter.api.Test;

/** Rock of every hardness fills as one, and a region is the band nearest its average. */
class MinecraftTerrainTest {

  private static final MinecraftTerrain TERRAIN = MinecraftTerrain.of(Environment.OVERWORLD);

  private static final MinecraftTerrain NETHER = MinecraftTerrain.of(Environment.NETHER);

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
  private static final MinecraftBlock GRAVEL = solid(MiningClass.LOOSE);

  /** Counts of rock cells, by class. */
  private static int[] counts(int soft, int rock, int hard) {
    int[] counts = new int[RegionClass.ID_BOUND];
    counts[RegionClass.SOFT_ROCK.id()] = soft;
    counts[RegionClass.ROCK.id()] = rock;
    counts[RegionClass.HARD_ROCK.id()] = hard;
    return counts;
  }

  private static RegionClass label(int[] counts) {
    return label(TERRAIN, counts);
  }

  private static RegionClass label(MinecraftTerrain terrain, int[] counts) {
    return RegionClass.byId(terrain.label(RegionClass.ROCK.id(), counts));
  }

  @Test
  void aRockRegionIsTheBandNearestItsAverageHardness() {
    assertEquals(RegionClass.ROCK, label(counts(0, 100, 0)));
    assertEquals(RegionClass.HARD_ROCK, label(counts(0, 0, 100)));
    assertEquals(RegionClass.MIXED_ROCK, label(counts(0, 50, 50)));
    assertEquals(RegionClass.ROCK, label(counts(0, 90, 10)), "a few veins do not make it mixed");
    assertEquals(RegionClass.HARD_ROCK, label(counts(0, 10, 90)));
    assertEquals(
        RegionClass.SOFT_ROCK, label(NETHER, counts(70, 30, 0)), "netherrack with some blackstone");
  }

  @Test
  void softRockIsAsHardAsTheDimensionMakesIt() {
    // Half soft rock, half hard rock: sandstone and deepslate average out past rock, netherrack and
    // deepslate do not.
    int[] half = counts(50, 0, 50);
    assertEquals(RegionClass.MIXED_ROCK, label(TERRAIN, half));
    assertEquals(RegionClass.ROCK, label(NETHER, half));
  }

  @Test
  void rockOfEveryHardnessIsOneFamilyAndDirtIsItsOwn() {
    int rock = RegionClass.ROCK.id();
    assertEquals(rock, TERRAIN.family(RegionClass.SOFT_ROCK.id()));
    assertEquals(rock, TERRAIN.family(RegionClass.HARD_ROCK.id()));
    assertEquals(RegionClass.LOOSE.id(), TERRAIN.family(RegionClass.LOOSE.id()));
    assertNotEquals(rock, TERRAIN.family(RegionClass.WOODEN.id()));
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
        new RegionColumnBuilder<>(new RegionSpec<>(TERRAIN, 0, 31));
    RegionColumn column = builder.build(0, 0, chunk);
    return column.regions();
  }

  @Test
  void aChangeMattersOnlyIfItChangesWhatClassesAreReadFrom() {
    // A crop growing another stage: passable before and after, so no cell's class moves.
    assertTrue(MinecraftTerrain.classifiesAlike(air(), air()));
    assertFalse(MinecraftTerrain.classifiesAlike(air(), STONE), "a wall where there was none");
    assertFalse(MinecraftTerrain.classifiesAlike(STONE, GRAVEL), "dug differently");
    assertTrue(MinecraftTerrain.classifiesAlike(STONE, solid(MiningClass.ROCK)), "ore for stone");
  }

  @Test
  void everyClassReadsBackFromItsId() {
    for (RegionClass clazz : RegionClass.values()) {
      assertEquals(clazz, RegionClass.byId(clazz.id()));
    }
    for (MiningClass mining : MiningClass.values()) {
      assertEquals(mining, RegionClass.of(mining).mining());
    }
  }
}
