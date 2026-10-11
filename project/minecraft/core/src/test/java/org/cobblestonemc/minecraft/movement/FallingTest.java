/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestMovements;
import org.cobblestonemc.minecraft.TestMovements.Moves;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class FallingTest {

  private final TestPlayer player = TestPlayer.walker();
  private final MinecraftMovementBehavior<CobblestonePlayer> walker =
      MinecraftMovementBehavior.forPlayer(player, Set.of());

  @Test
  void fallsOffAnEdgeAndCostsHealTimeForDamage() {
    // Player stands at (0,5,0) on a block at (0,4,0); to the east is an open shaft down to (1,0,0).
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 0, 0, TestBlocks.solid())
            .build();
    Movement<MinecraftStepPayload> landing =
        TestMovements.from(walker, player, world, new Cell(0, 5, 0)).to(new Cell(1, 1, 0));

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
  void oneBlockDropsAreWalked() {
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 3, 0, TestBlocks.solid())
            .build();
    Moves moves = TestMovements.from(walker, player, world, new Cell(0, 5, 0));
    assertFalse(moves.reaches(new Cell(1, 4, 0), MinecraftStepType.FALL));
    assertEquals(MinecraftStepType.WALK, moves.to(new Cell(1, 4, 0)).payload().stepType());
  }

  @Test
  void excludingFallsLeavesTheShaftUnused() {
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 0, 0, TestBlocks.solid())
            .build();
    MinecraftMovementBehavior<CobblestonePlayer> noFall =
        MinecraftMovementBehavior.forPlayer(player, Set.of(MinecraftStepType.FALL));
    assertFalse(
        TestMovements.from(noFall, player, world, new Cell(0, 5, 0)).reaches(new Cell(1, 1, 0)));
  }
}
