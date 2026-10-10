/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.junit.jupiter.api.Test;

class CoarseCostTest {

  private record TestBlock(boolean passable, boolean solidTop, double breakTime)
      implements MinecraftBlock {

    @Override
    public boolean isPassable() {
      return passable;
    }

    @Override
    public boolean isSolidTop() {
      return solidTop;
    }

    @Override
    public double breakTimeSeconds() {
      return breakTime;
    }
  }

  private static final MinecraftBlock AIR = new TestBlock(true, false, 0.0);

  /** Breaks in 1.5 s, so mining it is 2 * 1.5 + 0.2 = 3.2 s a block on the flat. */
  private static final MinecraftBlock STONE = new TestBlock(false, true, 1.5);

  private interface Shape {
    MinecraftBlock at(int x, int y, int z);
  }

  private static SectionProfile.Component section(Shape shape) {
    MinecraftChunk chunk = (x, y, z) -> shape.at(x, Math.floorMod(y, 16), z);
    return new SectionProfiler().profile(chunk, 0).whole();
  }

  private static final CoarseCost MINER =
      CoarseCost.forMediums(CoarseCost.survival()).withBlend(CoarseCost.Blend.SOLID);

  @Test
  void solidRockIsPricedAsMiningItInTheDirectionTravelled() {
    SectionProfile.Component rock = section((x, y, z) -> STONE);

    // Two blocks broken and a walk per block across; three and a diagonal walk per block up; one
    // and a fall per block down. What MineMode charges, and what a flat MINEABLE rate never could.
    assertEquals(16 * (2 * 1.5 + 0.2), MINER.crossingCost(rock, 16, 1, 0, 0), 1e-9);
    assertEquals(16 * (3 * 1.5 + 0.2 * Math.sqrt(2)), MINER.crossingCost(rock, 16, 0, 1, 0), 1e-9);
    assertEquals(16 * (1.5 + 0.18), MINER.crossingCost(rock, 16, 0, -1, 0), 1e-9);
  }

  @Test
  void aDiagonalTunnelIsAStaircaseOfAxisSteps() {
    SectionProfile.Component rock = section((x, y, z) -> STONE);

    // Mining moves along the four horizontal axes only, so crossing diagonally digs the Manhattan
    // distance: per block of straight-line travel, sqrt(2) blocks of tunnel.
    assertEquals(16 * Math.sqrt(2) * (2 * 1.5 + 0.2), MINER.crossingCost(rock, 16, 1, 0, 1), 1e-9);
  }

  @Test
  void climbingThroughRockGetsItsGroundForFree() {
    SectionProfile.Component rock = section((x, y, z) -> STONE);

    // Rising one block per block sideways is exactly a staircase: every step is a step up, and the
    // sideways half of the move costs nothing extra.
    double perBlock = (3 * 1.5 + 0.2 * Math.sqrt(2)) / Math.sqrt(2);
    assertEquals(16 * perBlock, MINER.crossingCost(rock, 16, 1, 1, 0), 1e-9);
  }

  @Test
  void openGroundIsPricedAsTheCheapestMediumAsBefore() {
    // Every slice holds air, so nothing has to be dug, and surface routes price as they always did.
    SectionProfile.Component ground = section((x, y, z) -> y <= 3 ? STONE : AIR);

    assertEquals(16 * 0.20, MINER.crossingCost(ground, 16, 1, 0, 0), 1e-9);
    assertEquals(
        CoarseCost.forMediums(CoarseCost.survival())
            .crossingCost(ground, 16, SectionProfile.Axis.X),
        MINER.crossingCost(ground, 16, 1, 0, 0),
        1e-9);
  }

  @Test
  void aWallAcrossTheWayIsDugThroughNotWalkedPast() {
    // Open ground for x < 8, solid rock for x >= 8: half the crossing must be mined. The cheapest
    // blend prices all of it at walking rate, because walking covers some slices.
    SectionProfile.Component wall = section((x, y, z) -> x >= 8 || y <= 3 ? STONE : AIR);

    assertEquals(8 * 0.20 + 8 * (2 * 1.5 + 0.2), MINER.crossingCost(wall, 16, 1, 0, 0), 1e-9);
    assertEquals(
        16 * 0.20,
        CoarseCost.forMediums(CoarseCost.survival()).crossingCost(wall, 16, SectionProfile.Axis.X),
        1e-9);
  }

  @Test
  void eachHalfOfACrossingIsPricedByTheHalfItPassesThrough() {
    // Rock in the lower half, sky in the upper. Climbing out through the top passes only through
    // sky; climbing in from below digs up through rock. Charging both the whole section's solid
    // share would price every hillside as half a tunnel, which is what it measured as.
    SectionProfile.Component hillside = section((x, y, z) -> y <= 7 ? STONE : AIR);

    assertEquals(8 * 0.20, MINER.halfCrossingCost(hillside, 8, 0, 1, 0, true), 1e-9);
    assertEquals(
        8 * (3 * 1.5 + 0.2 * Math.sqrt(2)),
        MINER.halfCrossingCost(hillside, 8, 0, 1, 0, false),
        1e-9);
    // And the other way: descending out of the bottom digs, descending in from the top does not.
    assertEquals(8 * (1.5 + 0.18), MINER.halfCrossingCost(hillside, 8, 0, -1, 0, true), 1e-9);
    assertEquals(8 * 0.20, MINER.halfCrossingCost(hillside, 8, 0, -1, 0, false), 1e-9);
  }

  @Test
  void aPlayerWhoCannotDigPaysTheFallbackForRock() {
    Set<Medium> noPickaxe = EnumSet.copyOf(CoarseCost.survival());
    noPickaxe.remove(Medium.MINEABLE);
    CoarseCost walker = CoarseCost.forMediums(noPickaxe).withBlend(CoarseCost.Blend.SOLID);

    assertEquals(
        16 * CoarseCost.FALLBACK_COST_PER_BLOCK,
        walker.crossingCost(section((x, y, z) -> STONE), 16, 1, 0, 0),
        1e-9);
  }
}
