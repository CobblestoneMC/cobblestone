/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestModes;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class FallModeTest {

  private final FallMode<CobblestonePlayer> fall = new FallMode<>();

  @Test
  void fallsOffAnEdgeAndCostsHealTimeForDamage() {
    // Player stands at (0,5,0) on a block at (0,4,0); to the east is an open shaft down to (1,0,0).
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 0, 0, TestBlocks.solid())
            .build();
    Map<Cell, Movement<MinecraftStepPayload>> moves =
        TestModes.from(fall, TestPlayer.walker(), world, new Cell(0, 5, 0));

    Movement<MinecraftStepPayload> landing = moves.get(new Cell(1, 1, 0));
    assertEquals(MinecraftStepType.FALL, landing.payload().stepType());
    double distance = 4;
    double expected =
        MovementCosts.WALK
            + MovementCosts.FALL_PER_BLOCK * distance
            + MovementCosts.DAMAGE_COST_MULTIPLIER
                * MovementCosts.HEAL_SECONDS_PER_HALF_HEART
                * (distance - MovementCosts.SAFE_FALL_BLOCKS);
    assertEquals(expected, landing.cost(), 1e-9);
  }

  @Test
  void oneBlockDropsAreLeftToWalkMode() {
    // A single-block step-down (landing one below) should not be offered by FallMode.
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 3, 0, TestBlocks.solid())
            .build();
    Map<Cell, Movement<MinecraftStepPayload>> moves =
        TestModes.from(fall, TestPlayer.walker(), world, new Cell(0, 5, 0));
    assertFalse(moves.containsKey(new Cell(1, 4, 0)));
  }
}
