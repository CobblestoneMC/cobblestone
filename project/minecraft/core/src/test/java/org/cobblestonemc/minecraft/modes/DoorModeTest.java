/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.Cell;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestModes;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class DoorModeTest {

  private final DoorMode<CobblestonePlayer> door = new DoorMode<>(true);

  @Test
  void walksThroughClosedWoodenDoorToTheFarSide() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.closedDoor(true))
            .build();
    var moves = TestModes.from(door, TestPlayer.walker(), world, new Cell(0, 1, 0));

    assertTrue(moves.containsKey(new Cell(2, 1, 0)));
    assertEquals(MinecraftStepType.OPEN_DOOR, moves.get(new Cell(2, 1, 0)).payload().stepType());
    assertEquals(
        MovementCosts.WALK + MovementCosts.OPEN_DOOR, moves.get(new Cell(2, 1, 0)).cost(), 1e-9);
  }

  @Test
  void willNotOpenClosedIronDoorWithoutAnActivator() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.closedDoor(false))
            .build();
    assertFalse(
        TestModes.from(door, TestPlayer.walker(), world, new Cell(0, 1, 0))
            .containsKey(new Cell(2, 1, 0)));
  }

  @Test
  void opensAnIronDoorWhenStandingOnPressurePlate() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(0, 1, 0, TestBlocks.pressurePlate())
            .set(1, 1, 0, TestBlocks.closedDoor(false))
            .build();
    assertTrue(
        TestModes.from(door, TestPlayer.walker(), world, new Cell(0, 1, 0))
            .containsKey(new Cell(2, 1, 0)));
  }
}
