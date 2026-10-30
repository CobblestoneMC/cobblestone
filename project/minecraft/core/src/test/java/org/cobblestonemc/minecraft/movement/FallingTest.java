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
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestMovements;
import org.cobblestonemc.minecraft.TestMovements.Moves;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.CostWeights;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class FallingTest {

  private final TestPlayer player = TestPlayer.walker().vulnerable();
  private final MinecraftMovementBehavior walker =
      MinecraftMovementBehavior.forPlayer(TestWorld.CHUNKS, player, Set.of());

  @Test
  void fallsOffAnEdgeAndCostsItsDamageButDoesNotTimeIt() {
    // Player stands at (0,5,0) on a block at (0,4,0); to the east is an open shaft down to (1,0,0).
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 0, 0, TestBlocks.solid())
            .build();
    Movement<MinecraftStepPayload> landing =
        TestMovements.from(walker, world, new Cell(0, 5, 0)).to(new Cell(1, 1, 0));

    assertEquals(MinecraftStepType.FALL, landing.payload().stepType());
    double distance = 4;
    double time = MovementCosts.WALK + MovementCosts.FALL_PER_BLOCK * distance;
    double halfHearts = distance - MovementCosts.SAFE_FALL_BLOCKS;
    assertEquals(time, landing.time(), 1e-9);
    assertEquals(time + CostWeights.defaults().damage(halfHearts), landing.cost(), 1e-9);
  }

  @Test
  void theDamageWeightPricesAFallsDamage() {
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 0, 0, TestBlocks.solid())
            .build();
    MinecraftMovementBehavior careless =
        MinecraftMovementBehavior.forPlayer(
            TestWorld.CHUNKS, player, Set.of(), null, 0, CostWeights.defaults().harmless());
    Movement<MinecraftStepPayload> landing =
        TestMovements.from(careless, world, new Cell(0, 5, 0)).to(new Cell(1, 1, 0));

    assertEquals(landing.time(), landing.cost(), 1e-9);
  }

  @Test
  void aFallIntoWaterDoesNoDamage() {
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 0, 0, TestBlocks.water())
            .build();
    Movement<MinecraftStepPayload> splash =
        TestMovements.from(walker, world, new Cell(0, 5, 0)).to(new Cell(1, 0, 0));

    assertEquals(MinecraftStepType.FALL, splash.payload().stepType());
    assertEquals(splash.time(), splash.cost(), 1e-9);
  }

  @Test
  void oneBlockDropsAreWalked() {
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 3, 0, TestBlocks.solid())
            .build();
    Moves moves = TestMovements.from(walker, world, new Cell(0, 5, 0));
    assertFalse(moves.reaches(new Cell(1, 4, 0), MinecraftStepType.FALL));
    assertEquals(MinecraftStepType.WALK, moves.to(new Cell(1, 4, 0)).payload().stepType());
  }

  @Test
  void aOneBlockDropIntoWaterIsSwum() {
    // Player stands on ice at (0,4,0); to the east the water's surface is a block lower.
    TestWorld world = iceBesideWater();
    Movement<MinecraftStepPayload> entering =
        TestMovements.from(walker, world, new Cell(0, 5, 0)).to(new Cell(1, 4, 0));

    assertEquals(MinecraftStepType.SWIM, entering.payload().stepType());
    assertEquals(MovementCosts.SWIM * MovementCosts.DIAGONAL, entering.time(), 1e-9);
  }

  @Test
  void excludingSwimmingKeepsAPlayerOutOfTheWaterBelow() {
    MinecraftMovementBehavior noSwim =
        MinecraftMovementBehavior.forPlayer(
            TestWorld.CHUNKS, player, Set.of(MinecraftStepType.SWIM));
    assertFalse(
        TestMovements.from(noSwim, iceBesideWater(), new Cell(0, 5, 0)).reaches(new Cell(1, 4, 0)));
  }

  private static TestWorld iceBesideWater() {
    return TestWorld.builder("w")
        .set(0, 4, 0, TestBlocks.ice())
        .set(1, 4, 0, TestBlocks.water())
        .set(1, 3, 0, TestBlocks.water())
        .set(1, 2, 0, TestBlocks.solid())
        .build();
  }

  @Test
  void excludingFallsLeavesTheShaftUnused() {
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 4, 0, TestBlocks.solid())
            .set(1, 0, 0, TestBlocks.solid())
            .build();
    MinecraftMovementBehavior noFall =
        MinecraftMovementBehavior.forPlayer(
            TestWorld.CHUNKS, player, Set.of(MinecraftStepType.FALL));
    assertFalse(TestMovements.from(noFall, world, new Cell(0, 5, 0)).reaches(new Cell(1, 1, 0)));
  }
}
