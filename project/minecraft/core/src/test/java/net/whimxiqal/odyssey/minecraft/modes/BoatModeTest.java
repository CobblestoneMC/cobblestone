/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.TestBlocks;
import net.whimxiqal.odyssey.minecraft.TestModes;
import net.whimxiqal.odyssey.minecraft.TestPlayer;
import net.whimxiqal.odyssey.minecraft.TestWorld;
import net.whimxiqal.odyssey.minecraft.api.*;
import org.junit.jupiter.api.Test;

class BoatModeTest {

  private final BoatMode<OdysseyPlayer> boat = new BoatMode<>();
  private final OdysseyPlayer withBoat = TestPlayer.create(false, true, false, true);

  @Test
  void enteringWaterPlacesBoatAndSetsVehicleState() {
    TestWorld world = TestWorld.builder("w")
        .floor(0, -1, -1, 0, 1, TestBlocks.solid())
        .set(1, 1, 0, TestBlocks.water())
        .build();
    Movement<MinecraftStepPayload> place =
        TestModes.from(boat, withBoat, world, new Cell(0, 1, 0)).get(new Cell(1, 1, 0));

    assertEquals(MinecraftStepType.PLACE_BOAT, place.payload().stepType());
    assertEquals(MovementCosts.PLACE_BOAT, place.cost(), 1e-9);
    assertEquals(MinecraftKeys.Vehicle.BOAT, place.state().get(MinecraftKeys.VEHICLE));
  }

  @Test
  void travelsAcrossWaterWhileBoating() {
    TestWorld world = TestWorld.builder("w")
        .set(1, 1, 0, TestBlocks.water())
        .set(2, 1, 0, TestBlocks.water())
        .build();
    TraversalState boating = TraversalState.DEFAULT.with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.BOAT);
    Movement<MinecraftStepPayload> travel =
        TestModes.from(boat, withBoat, world, new Cell(1, 1, 0), boating).get(new Cell(2, 1, 0));

    assertEquals(MinecraftStepType.BOAT, travel.payload().stepType());
    assertEquals(MovementCosts.BOAT, travel.cost(), 1e-9);
    assertEquals(MinecraftKeys.Vehicle.BOAT, travel.state().get(MinecraftKeys.VEHICLE));
  }

  @Test
  void doesNotApplyWithoutBoatAndOnFoot() {
    // On foot with no vehicle state but the mode present: it should still only act at water's edge.
    TestWorld dryLand = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    assertTrue(TestModes.from(boat, withBoat, dryLand, new Cell(0, 1, 0)).isEmpty());
  }
}
