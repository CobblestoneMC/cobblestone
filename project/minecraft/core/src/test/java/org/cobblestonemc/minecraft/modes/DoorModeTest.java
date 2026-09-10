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

  /**
   * A door left standing open used to be a permanent wall: {@code DoorMode} skipped it as
   * "WalkMode's job", but a door is impassable at the material level whichever way it stands, so
   * WalkMode would not cross it either. Nothing could pass — which quietly walls off any building
   * whose door happens to be open.
   */
  @Test
  void walksThroughAnOpenDoorToTheFarSide() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.openDoor())
            .build();
    var moves = TestModes.from(door, TestPlayer.walker(), world, new Cell(0, 1, 0));

    assertTrue(moves.containsKey(new Cell(2, 1, 0)), "an open door must be crossable");
    assertEquals(MinecraftStepType.WALK, moves.get(new Cell(2, 1, 0)).payload().stepType());
    assertEquals(MovementCosts.WALK, moves.get(new Cell(2, 1, 0)).cost(), 1e-9);
  }

  /** An open door costs no opening time, so it must be cheaper than working a shut one. */
  @Test
  void anOpenDoorIsCheaperThanOpeningAShutOne() {
    TestWorld open =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.openDoor())
            .build();
    TestWorld shut =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.closedDoor(true))
            .build();
    Cell far = new Cell(2, 1, 0);
    assertTrue(
        TestModes.from(door, TestPlayer.walker(), open, new Cell(0, 1, 0)).get(far).cost()
            < TestModes.from(door, TestPlayer.walker(), shut, new Cell(0, 1, 0)).get(far).cost());
  }

  /**
   * {@code -no-open-door} must actually stop the mode opening doors; the flag was being ignored.
   */
  @Test
  void aPlayerForbiddenFromOpeningDoorsCannotWorkAShutOne() {
    DoorMode<CobblestonePlayer> cannotOpen = new DoorMode<>(false);
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.closedDoor(true))
            .build();
    assertFalse(
        TestModes.from(cannotOpen, TestPlayer.walker(), world, new Cell(0, 1, 0))
            .containsKey(new Cell(2, 1, 0)));
  }

  /** …but an already-open door needs no opening, so the flag must not bar it. */
  @Test
  void aPlayerForbiddenFromOpeningDoorsStillWalksThroughAnOpenOne() {
    DoorMode<CobblestonePlayer> cannotOpen = new DoorMode<>(false);
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 3, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.openDoor())
            .build();
    assertTrue(
        TestModes.from(cannotOpen, TestPlayer.walker(), world, new Cell(0, 1, 0))
            .containsKey(new Cell(2, 1, 0)));
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
