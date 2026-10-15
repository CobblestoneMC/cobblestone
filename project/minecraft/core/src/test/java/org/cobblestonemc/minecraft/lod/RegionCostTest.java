/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

/** Each region is crossed by the cheapest capability the agent has for its class. */
class RegionCostTest {

  /** Ten blocks along X. */
  private static double across(RegionCost cost, RegionClass klass) {
    return cost.traverse(klass, 0, 0, 0, 0, 10, 0, 0) / 10;
  }

  @Test
  void soulSandDragsAWalkerAndSoulSoilDoesNot() {
    RegionCost walker = RegionCost.forPlayer(TestPlayer.walker(), Set.of());
    assertEquals(0.20, across(walker, RegionClass.STAND), 1e-9);
    assertEquals(0.20 / 0.4, across(walker, RegionClass.SOUL_SAND), 1e-9);
    assertEquals(0.20, across(walker, RegionClass.SOUL_SOIL), 1e-9);
  }

  @Test
  void soulSpeedBootsMakeBothSoulBlocksFasterThanGround() {
    RegionCost boots = RegionCost.forPlayer(TestPlayer.soulSpeed(3), Set.of());
    double fast = 0.20 / (1 + 0.3 * (1 + 0.35 * 3));
    assertEquals(fast, across(boots, RegionClass.SOUL_SAND), 1e-9);
    assertEquals(fast, across(boots, RegionClass.SOUL_SOIL), 1e-9);
    assertEquals(0.20, across(boots, RegionClass.STAND), 1e-9, "the boots do nothing elsewhere");
    assertEquals(fast, boots.cheapest(), 1e-9, "and the bound knows it");
  }

  @Test
  void soulSpeedGoesWithWalking() {
    Set<Capability> capabilities =
        RegionCost.capabilitiesFor(TestPlayer.soulSpeed(1), Set.of(MinecraftStepType.WALK));
    assertFalse(capabilities.contains(Capability.SOUL_SPEED));
  }

  @Test
  void onlyTheSurfaceCarriesABoat() {
    Set<Capability> boatOnly = EnumSet.of(Capability.WALK, Capability.BOAT);
    RegionCost boater = RegionCost.forCapabilities(boatOnly);
    assertTrue(boater.usable(RegionClass.WATER_SURFACE));
    assertFalse(boater.usable(RegionClass.WATER), "below the surface is swum or nothing");
    assertEquals(0.15, across(boater, RegionClass.WATER_SURFACE), 1e-9);

    RegionCost swimmer = RegionCost.forCapabilities(RegionCost.survival());
    assertTrue(swimmer.usable(RegionClass.WATER));
    assertEquals(0.30, across(swimmer, RegionClass.WATER), 1e-9);
    assertEquals(0.30, across(swimmer, RegionClass.WATER_SURFACE), 1e-9);

    Set<Capability> both = EnumSet.copyOf(RegionCost.survival());
    both.add(Capability.BOAT);
    RegionCost swimmingBoater = RegionCost.forCapabilities(both);
    assertEquals(0.15, across(swimmingBoater, RegionClass.WATER_SURFACE), 1e-9);
    assertEquals(0.30, across(swimmingBoater, RegionClass.WATER), 1e-9);
  }

  @Test
  void aBoatSwimsTheDropsInASurface() {
    Set<Capability> both = EnumSet.copyOf(RegionCost.survival());
    both.add(Capability.BOAT);
    RegionCost boater = RegionCost.forCapabilities(both);
    // Ten blocks across and two down: boated across, swum down.
    assertEquals(
        0.15 * 10 + 0.30 * 2,
        boater.traverse(RegionClass.WATER_SURFACE, 0, 0, 2, 0, 10, 0, 0),
        1e-9);
  }
}
