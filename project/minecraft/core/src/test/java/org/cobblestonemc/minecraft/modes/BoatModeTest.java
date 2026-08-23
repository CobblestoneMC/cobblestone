/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestModes;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class BoatModeTest {

  private final BoatMode<CobblestonePlayer> boat = new BoatMode<>();
  private final CobblestonePlayer withBoat = TestPlayer.create(false, true, false, true);

  @Test
  void enteringWaterPlacesBoatAndSetsVehicleState() {
    TestWorld world =
        TestWorld.builder("w")
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
    TestWorld world =
        TestWorld.builder("w")
            .set(1, 1, 0, TestBlocks.water())
            .set(2, 1, 0, TestBlocks.water())
            .build();
    TraversalState boating =
        TraversalState.DEFAULT.with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.BOAT);
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
