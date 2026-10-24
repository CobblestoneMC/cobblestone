/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

/** Each region is crossed by the cheapest way the agent has across its class. */
class MinecraftAgentTest {

  /** Ten blocks along X. */
  private static double across(MinecraftAgent cost, RegionClass klass) {
    return cost.traverse(klass, 0, 0, 0, 10, 0, 0) / 10;
  }

  @Test
  void soulSandDragsAWalkerAndSoulSoilDoesNot() {
    MinecraftAgent walker = MinecraftAgent.forPlayer(TestPlayer.walker(), Set.of());
    assertEquals(0.20, across(walker, RegionClass.STAND), 1e-9);
    assertEquals(0.20 / 0.4, across(walker, RegionClass.SOUL_SAND), 1e-9);
    assertEquals(0.20, across(walker, RegionClass.SOUL_SOIL), 1e-9);
  }

  @Test
  void soulSpeedBootsMakeBothSoulBlocksFasterThanGround() {
    MinecraftAgent boots = MinecraftAgent.forPlayer(TestPlayer.soulSpeed(3), Set.of());
    double fast = 0.20 / (1 + 0.3 * (1 + 0.35 * 3));
    assertEquals(fast, across(boots, RegionClass.SOUL_SAND), 1e-9);
    assertEquals(fast, across(boots, RegionClass.SOUL_SOIL), 1e-9);
    assertEquals(0.20, across(boots, RegionClass.STAND), 1e-9, "the boots do nothing elsewhere");
    assertEquals(fast, boots.cheapest(), 1e-9, "and the bound knows it");
  }

  @Test
  void soulSpeedGoesWithWalking() {
    MinecraftAgent boots =
        MinecraftAgent.forPlayer(TestPlayer.soulSpeed(1), Set.of(MinecraftStepType.WALK));
    assertFalse(boots.usable(RegionClass.SOUL_SOIL));
  }

  @Test
  void onlyTheSurfaceCarriesABoat() {
    MinecraftAgent boatOnly =
        MinecraftAgent.forPlayer(
            TestPlayer.create(false, true, false), Set.of(MinecraftStepType.SWIM));
    assertTrue(boatOnly.usable(RegionClass.WATER_SURFACE));
    assertFalse(boatOnly.usable(RegionClass.WATER), "below the surface is swum or nothing");
    assertEquals(0.15, across(boatOnly, RegionClass.WATER_SURFACE), 1e-9);

    MinecraftAgent swimmer = MinecraftAgent.forPlayer(TestPlayer.walker(), Set.of());
    assertTrue(swimmer.usable(RegionClass.WATER));
    assertEquals(0.30, across(swimmer, RegionClass.WATER), 1e-9);
    assertEquals(0.30, across(swimmer, RegionClass.WATER_SURFACE), 1e-9);

    MinecraftAgent boater =
        MinecraftAgent.forPlayer(TestPlayer.create(false, true, false), Set.of());
    assertEquals(0.15, across(boater, RegionClass.WATER_SURFACE), 1e-9);
    assertEquals(0.30, across(boater, RegionClass.WATER), 1e-9);
  }

  @Test
  void eachMiningBucketIsPricedAsItsTypicalBlock() {
    MinecraftAgent miner = MinecraftAgent.forPlayer(TestPlayer.walker(), Set.of());
    // Ten blocks level: two blocks broken and a walk per block, at hardness times 1.5 a block.
    assertEquals(2 * 1.5 * 1.5 + 0.20, across(miner, RegionClass.STONE), 1e-9);
    assertTrue(across(miner, RegionClass.SANDSTONE) < across(miner, RegionClass.STONE));
    assertTrue(across(miner, RegionClass.STONE) < across(miner, RegionClass.DEEPSLATE));
    assertTrue(across(miner, RegionClass.DEEPSLATE) < across(miner, RegionClass.OBSIDIAN));

    MinecraftAgent noPickaxe =
        MinecraftAgent.forPlayer(TestPlayer.walker(), Set.of(MinecraftStepType.MINE));
    assertEquals(
        MinecraftAgent.FALLBACK_COST_PER_BLOCK, across(noPickaxe, RegionClass.STONE), 1e-9);
  }

  @Test
  void aBoatSwimsTheDropsInASurface() {
    MinecraftAgent boater =
        MinecraftAgent.forPlayer(TestPlayer.create(false, true, false), Set.of());
    // Ten blocks across and two down: boated across, swum down.
    assertEquals(
        0.15 * 10 + 0.30 * 2, boater.traverse(RegionClass.WATER_SURFACE, 0, 2, 0, 10, 0, 0), 1e-9);
  }
}
