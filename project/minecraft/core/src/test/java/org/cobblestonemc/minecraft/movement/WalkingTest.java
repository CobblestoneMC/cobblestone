/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class WalkingTest {

  private final TestPlayer player = TestPlayer.walker();
  private final MinecraftMovementBehavior<CobblestonePlayer> walker =
      MinecraftMovementBehavior.forPlayer(player, Set.of());

  @Test
  void flatMovesInAllEightDirectionsAndNothingElse() {
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    Moves moves = TestMovements.from(walker, player, world, new Cell(0, 1, 0));

    assertEquals(8, moves.size(), "flat ground is walked; nothing else applies");
    assertEquals(8, moves.ofType(MinecraftStepType.WALK).size());
    assertEquals(MovementCosts.WALK, moves.to(new Cell(1, 1, 0)).cost(), 1e-9);
    assertEquals(
        MovementCosts.WALK * MovementCosts.DIAGONAL, moves.to(new Cell(1, 1, 1)).cost(), 1e-9);
  }

  @Test
  void diagonalIsBlockedBySolidCorner() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 1, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.solid()) // one corner of the NE-ish diagonal is a wall
            .build();
    Moves moves = TestMovements.from(walker, player, world, new Cell(0, 1, 0));

    assertFalse(moves.reaches(new Cell(1, 1, 1)), "cannot cut the corner through a solid block");
    assertFalse(
        moves.reaches(new Cell(1, 1, 0), MinecraftStepType.WALK),
        "the corner block itself is not standable");
    assertEquals(
        MinecraftStepType.MINE,
        moves.to(new Cell(1, 1, 0)).payload().stepType(),
        "but it can be mined through");
  }

  @Test
  void jumpsUpOntoFullBlockButWalksUpOntoSlab() {
    TestWorld full =
        TestWorld.builder("w")
            .floor(0, -1, -1, 1, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.solid())
            .build();
    Movement<MinecraftStepPayload> jump =
        TestMovements.from(walker, player, full, new Cell(0, 1, 0)).to(new Cell(1, 2, 0));
    assertEquals(MinecraftStepType.JUMP, jump.payload().stepType());
    assertEquals(MovementCosts.WALK * MovementCosts.DIAGONAL, jump.cost(), 1e-9);

    TestWorld slab =
        TestWorld.builder("w")
            .floor(0, -1, -1, 1, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.slab())
            .build();
    Movement<MinecraftStepPayload> step =
        TestMovements.from(walker, player, slab, new Cell(0, 1, 0)).to(new Cell(1, 2, 0));
    assertEquals(MinecraftStepType.WALK, step.payload().stepType());
    assertEquals(MovementCosts.WALK * MovementCosts.DIAGONAL, step.cost(), 1e-9);
  }

  @Test
  void stepsDownOneBlock() {
    TestWorld world =
        TestWorld.builder("w")
            .set(0, 0, 0, TestBlocks.solid())
            .set(1, -1, 0, TestBlocks.solid())
            .build();
    Movement<MinecraftStepPayload> down =
        TestMovements.from(walker, player, world, new Cell(0, 1, 0)).to(new Cell(1, 0, 0));
    assertEquals(MinecraftStepType.WALK, down.payload().stepType());
    assertEquals(MovementCosts.WALK * MovementCosts.DIAGONAL, down.cost(), 1e-9);
  }

  /**
   * Slow ground is priced as slow walking. Mining offered a free "tunnel" through the open air over
   * it at plain walking cost, which undercut the walk; a move that breaks nothing is not mining.
   */
  @Test
  void speedFactorScalesCost() {
    TestWorld ice = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.ice()).build();
    assertEquals(
        MovementCosts.WALK / 2.0,
        TestMovements.from(walker, player, ice, new Cell(0, 1, 0)).to(new Cell(1, 1, 0)).cost(),
        1e-9);

    TestWorld soul = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.soulSand()).build();
    Movement<MinecraftStepPayload> slow =
        TestMovements.from(walker, player, soul, new Cell(0, 1, 0)).to(new Cell(1, 1, 0));
    assertEquals(MinecraftStepType.WALK, slow.payload().stepType());
    assertEquals(MovementCosts.WALK / 0.4, slow.cost(), 1e-9);
  }

  @Test
  void aMountedPlayerRidesRatherThanWalks() {
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    var mounted = TraversalState.DEFAULT.with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.HORSE);
    Moves moves = TestMovements.from(walker, player, world, new Cell(0, 1, 0), mounted);

    assertEquals(8, moves.size());
    assertTrue(moves.ofType(MinecraftStepType.WALK).isEmpty());
    assertEquals(8, moves.ofType(MinecraftStepType.HORSE).size());
  }
}
