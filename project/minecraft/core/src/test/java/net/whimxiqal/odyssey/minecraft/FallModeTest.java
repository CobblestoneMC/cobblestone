/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Movement;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.OdysseyPlayer;
import org.junit.jupiter.api.Test;

class FallModeTest {

  private final FallMode<OdysseyPlayer> fall = new FallMode<>();

  @Test
  void fallsOffAnEdgeAndCostsHealTimeForDamage() {
    // Player stands at (0,5,0) on a block at (0,4,0); to the east is an open shaft down to (1,0,0).
    TestWorld world = TestWorld.builder("w")
        .set(0, 4, 0, TestBlocks.solid())
        .set(1, 0, 0, TestBlocks.solid())
        .build();
    Map<Cell, Movement<MinecraftStepType, MinecraftInstruction>> moves =
        TestModes.from(fall, TestPlayer.walker(), world, new Cell(0, 5, 0));

    Movement<MinecraftStepType, MinecraftInstruction> landing = moves.get(new Cell(1, 1, 0));
    assertEquals(MinecraftStepType.FALL, landing.stepType());
    double distance = 4;
    double expected = MovementCosts.WALK
        + MovementCosts.FALL_PER_BLOCK * distance
        + MovementCosts.DAMAGE_COST_MULTIPLIER * MovementCosts.HEAL_SECONDS_PER_HALF_HEART
            * (distance - MovementCosts.SAFE_FALL_BLOCKS);
    assertEquals(expected, landing.cost(), 1e-9);
  }

  @Test
  void oneBlockDropsAreLeftToWalkMode() {
    // A single-block step-down (landing one below) should not be offered by FallMode.
    TestWorld world = TestWorld.builder("w")
        .set(0, 4, 0, TestBlocks.solid())
        .set(1, 3, 0, TestBlocks.solid())
        .build();
    Map<Cell, Movement<MinecraftStepType, MinecraftInstruction>> moves =
        TestModes.from(fall, TestPlayer.walker(), world, new Cell(0, 5, 0));
    assertFalse(moves.containsKey(new Cell(1, 4, 0)));
  }
}
