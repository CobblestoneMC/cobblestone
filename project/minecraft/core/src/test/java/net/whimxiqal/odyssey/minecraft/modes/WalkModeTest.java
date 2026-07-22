/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.*;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.OdysseyPlayer;
import org.junit.jupiter.api.Test;

class WalkModeTest {

  private final WalkMode<OdysseyPlayer> walk = new WalkMode<>();
  private final TestPlayer player = TestPlayer.walker();

  @Test
  void flatMovesInAllEightDirections() {
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    Map<Cell, Movement<MinecraftStepPayload>> moves =
        TestModes.from(walk, player, world, new Cell(0, 1, 0));

    assertEquals(8, moves.size());
    assertEquals(MovementCosts.WALK, moves.get(new Cell(1, 1, 0)).cost(), 1e-9);
    assertEquals(MovementCosts.WALK * MovementCosts.DIAGONAL, moves.get(new Cell(1, 1, 1)).cost(), 1e-9);
    assertEquals(MinecraftStepType.WALK, moves.get(new Cell(1, 1, 0)).payload().stepType());
  }

  @Test
  void diagonalIsBlockedBySolidCorner() {
    TestWorld world = TestWorld.builder("w")
        .floor(0, -1, -1, 1, 1, TestBlocks.solid())
        .set(1, 1, 0, TestBlocks.solid()) // one corner of the NE-ish diagonal is a wall
        .build();
    Map<Cell, Movement<MinecraftStepPayload>> moves =
        TestModes.from(walk, player, world, new Cell(0, 1, 0));

    assertFalse(moves.containsKey(new Cell(1, 1, 1)), "cannot cut the corner through a solid block");
    assertFalse(moves.containsKey(new Cell(1, 1, 0)), "the corner block itself is not standable");
  }

  @Test
  void jumpsUpOntoFullBlockButWalksUpOntoSlab() {
    TestWorld full = TestWorld.builder("w")
        .floor(0, -1, -1, 1, 1, TestBlocks.solid())
        .set(1, 1, 0, TestBlocks.solid())
        .build();
    Movement<MinecraftStepPayload> jump =
        TestModes.from(walk, player, full, new Cell(0, 1, 0)).get(new Cell(1, 2, 0));
    assertEquals(MinecraftStepType.JUMP, jump.payload().stepType());
    assertEquals(MovementCosts.WALK + MovementCosts.JUMP_EXTRA, jump.cost(), 1e-9);

    TestWorld slab = TestWorld.builder("w")
        .floor(0, -1, -1, 1, 1, TestBlocks.solid())
        .set(1, 1, 0, TestBlocks.slab())
        .build();
    Movement<MinecraftStepPayload> step =
        TestModes.from(walk, player, slab, new Cell(0, 1, 0)).get(new Cell(1, 2, 0));
    assertEquals(MinecraftStepType.WALK, step.payload().stepType());
    assertEquals(MovementCosts.WALK, step.cost(), 1e-9);
  }

  @Test
  void speedFactorScalesCost() {
    TestWorld ice = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.ice()).build();
    assertEquals(MovementCosts.WALK / 2.0,
        TestModes.from(walk, player, ice, new Cell(0, 1, 0)).get(new Cell(1, 1, 0)).cost(), 1e-9);

    TestWorld soul = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.soulSand()).build();
    assertEquals(MovementCosts.WALK / 0.4,
        TestModes.from(walk, player, soul, new Cell(0, 1, 0)).get(new Cell(1, 1, 0)).cost(), 1e-9);
  }

  @Test
  void doesNotApplyWhileMounted() {
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    var mounted = TraversalState.DEFAULT
        .with(MinecraftKeys.VEHICLE,
            MinecraftKeys.Vehicle.HORSE);
    assertTrue(TestModes.from(walk, player, world, new Cell(0, 1, 0), mounted).isEmpty());
  }
}
