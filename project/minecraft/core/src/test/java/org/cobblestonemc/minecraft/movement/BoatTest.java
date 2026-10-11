/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestMovements;
import org.cobblestonemc.minecraft.TestMovements.Moves;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class BoatTest {

  private final CobblestonePlayer withBoat = TestPlayer.create(false, true, false);
  private final MinecraftMovementBehavior boat =
      MinecraftMovementBehavior.forPlayer(withBoat, Set.of());

  @Test
  void enteringWaterPlacesBoatAndSetsVehicleState() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 0, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.water())
            .build();
    Movement<MinecraftStepPayload> place =
        TestMovements.from(boat, world, new Cell(0, 1, 0))
            .to(new Cell(1, 1, 0), MinecraftStepType.PLACE_BOAT);

    assertEquals(MinecraftStepType.PLACE_BOAT, place.payload().stepType());
    assertEquals(MovementCosts.PLACE_BOAT, place.cost(), 1e-9);
    assertEquals(MinecraftKeys.Vehicle.BOAT, place.state().get(MinecraftKeys.VEHICLE));
  }

  @Test
  void travelsAcrossWaterWhileBoating() {
    TestWorld world =
        TestWorld.builder("w")
            .set(1, 1, 0, TestBlocks.water())
            .set(2, 1, 0, TestBlocks.water())
            .build();
    TraversalState boating =
        TraversalState.DEFAULT.with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.BOAT);
    Movement<MinecraftStepPayload> travel =
        TestMovements.from(boat, world, new Cell(1, 1, 0), boating).to(new Cell(2, 1, 0));

    assertEquals(MinecraftStepType.BOAT, travel.payload().stepType());
    assertEquals(MovementCosts.BOAT, travel.cost(), 1e-9);
    assertEquals(MinecraftKeys.Vehicle.BOAT, travel.state().get(MinecraftKeys.VEHICLE));
  }

  /** A boat on water at x=1, with land to the west and more water to the east. */
  private static TestWorld shore() {
    return TestWorld.builder("w")
        .set(0, 0, 0, TestBlocks.solid())
        .set(1, 1, 0, TestBlocks.water())
        .set(2, 1, 0, TestBlocks.water())
        .build();
  }

  private static final TraversalState BOATING =
      TraversalState.DEFAULT.with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.BOAT);

  @Test
  void getsOutOfTheBoatOntoLandOrIntoWater() {
    Moves moves = TestMovements.from(boat, shore(), new Cell(1, 1, 0), BOATING);

    Movement<MinecraftStepPayload> ashore = moves.to(new Cell(0, 1, 0));
    assertEquals(MinecraftStepType.WALK, ashore.payload().stepType());
    assertNull(ashore.state().get(MinecraftKeys.VEHICLE));

    Movement<MinecraftStepPayload> overboard = moves.to(new Cell(2, 1, 0), MinecraftStepType.SWIM);
    assertNull(overboard.state().get(MinecraftKeys.VEHICLE));
  }

  /** Excluding boats stops a player in one from paddling, not from getting out. */
  @Test
  void excludingBoatsStillLetsAPlayerInOneGetOut() {
    MinecraftMovementBehavior noBoat =
        MinecraftMovementBehavior.forPlayer(withBoat, Set.of(MinecraftStepType.BOAT));
    Moves moves = TestMovements.from(noBoat, shore(), new Cell(1, 1, 0), BOATING);

    assertTrue(moves.ofType(MinecraftStepType.BOAT).isEmpty());
    assertTrue(moves.reaches(new Cell(0, 1, 0), MinecraftStepType.WALK));
    assertTrue(moves.reaches(new Cell(2, 1, 0), MinecraftStepType.SWIM));
  }

  @Test
  void offersNoBoatOnDryLand() {
    // Dismounted with a boat in hand, the boat only comes out at water's edge.
    TestWorld dryLand = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    Moves moves = TestMovements.from(boat, dryLand, new Cell(0, 1, 0));
    assertTrue(moves.ofType(MinecraftStepType.PLACE_BOAT).isEmpty());
    assertTrue(moves.ofType(MinecraftStepType.BOAT).isEmpty());
  }

  /** Without a boat in hand, water's edge is just water. */
  @Test
  void aPlayerWithoutABoatCannotPlaceOne() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 0, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.water())
            .build();
    CobblestonePlayer walker = TestPlayer.walker();
    assertTrue(
        TestMovements.from(
                MinecraftMovementBehavior.forPlayer(walker, Set.of()), world, new Cell(0, 1, 0))
            .ofType(MinecraftStepType.PLACE_BOAT)
            .isEmpty());
  }
}
