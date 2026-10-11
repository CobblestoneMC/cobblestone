/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;
import org.cobblestonemc.Movement;
import org.cobblestonemc.minecraft.BreakChecker;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestMovements;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class MiningTest {

  private final TestPlayer player = TestPlayer.walker();
  private final MinecraftMovementBehavior<CobblestonePlayer> walker =
      MinecraftMovementBehavior.forPlayer(player, Set.of());

  private static TestWorld wallTo(int wallX, MinecraftBlock feet, MinecraftBlock head) {
    return TestWorld.builder("w")
        .floor(0, -1, -1, 2, 1, TestBlocks.solid())
        .set(wallX, 1, 0, feet)
        .set(wallX, 2, 0, head)
        .build();
  }

  @Test
  void tunnelsThroughBreakableWall() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    Movement<MinecraftStepPayload> dig =
        TestMovements.from(walker, player, world, new Cell(0, 1, 0)).to(new Cell(1, 1, 0));

    assertEquals(MinecraftStepType.MINE, dig.payload().stepType());
    // break feet (2s) + head (2s) + a walk step
    assertEquals(2.0 + 2.0 + MovementCosts.WALK, dig.cost(), 1e-9);
  }

  /** Mining is for blocks in the way; where there are none, the move is a walk. */
  @Test
  void aClearPathIsWalkedNotMined() {
    TestWorld world = TestWorld.builder("w").floor(0, -1, -1, 1, 1, TestBlocks.solid()).build();
    assertTrue(
        TestMovements.from(walker, player, world, new Cell(0, 1, 0))
            .ofType(MinecraftStepType.MINE)
            .isEmpty());
  }

  @Test
  void willNotMineUnbreakableBlocks() {
    TestWorld world = wallTo(1, TestBlocks.bedrock(), TestBlocks.solid(2.0));
    assertFalse(
        TestMovements.from(walker, player, world, new Cell(0, 1, 0)).reaches(new Cell(1, 1, 0)));
  }

  @Test
  void willNotTunnelBesideLava() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 2, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.solid(2.0))
            .set(1, 2, 0, TestBlocks.solid(2.0))
            .set(2, 2, 0, TestBlocks.lava()) // behind the head block, two blocks out
            .build();
    assertFalse(
        TestMovements.from(walker, player, world, new Cell(0, 1, 0)).reaches(new Cell(1, 1, 0)));
  }

  /** The block above a mined step's landing is also checked: its far face is two blocks out. */
  @Test
  void willNotMineUpAStepBesideLava() {
    TestWorld world =
        TestWorld.builder("w")
            .floor(0, -1, -1, 2, 1, TestBlocks.solid())
            .set(1, 1, 0, TestBlocks.solid()) // the step
            .set(1, 2, 0, TestBlocks.solid(2.0))
            .set(1, 3, 0, TestBlocks.solid(2.0))
            .set(2, 3, 0, TestBlocks.lava())
            .build();
    assertFalse(
        TestMovements.from(walker, player, world, new Cell(0, 1, 0))
            .reaches(new Cell(1, 2, 0), MinecraftStepType.MINE));
  }

  @Test
  void willNotMineWhenBreakingIsNotAllowed() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    CobblestonePlayer cannotBreak = TestPlayer.create(false, false, false, false);
    assertFalse(
        TestMovements.from(
                MinecraftMovementBehavior.forPlayer(cannotBreak, Set.of()),
                cannotBreak,
                world,
                new Cell(0, 1, 0))
            .reaches(new Cell(1, 1, 0)));
  }

  @Test
  void willNotMineWhenMiningIsExcluded() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    assertFalse(
        TestMovements.from(
                MinecraftMovementBehavior.forPlayer(player, Set.of(MinecraftStepType.MINE)),
                player,
                world,
                new Cell(0, 1, 0))
            .reaches(new Cell(1, 1, 0)));
  }

  @Test
  void injectedBreakCheckerTagsTheEdgeAsRestrictedOptimistically() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    // An integration forbids breaking the block at the wall's feet. The tunnel move is still
    // emitted (optimistically), but tagged with a restricted future that resolves true — the search
    // drops the edge when it does.
    BreakChecker<CobblestonePlayer> forbidWall =
        (agent, cell, breakWorld, block) ->
            CompletableFuture.completedFuture(!cell.equals(new Cell(1, 1, 0)));
    MinecraftMovementBehavior<CobblestonePlayer> checked =
        MinecraftMovementBehavior.forPlayer(player, Set.of(), forbidWall, 0);

    Movement<MinecraftStepPayload> tunnel =
        TestMovements.from(checked, player, world, new Cell(0, 1, 0)).to(new Cell(1, 1, 0));
    assertNotNull(tunnel, "the move is emitted optimistically");
    assertNotNull(tunnel.restricted(), "and carries a breakability check");
    assertTrue(
        tunnel.restricted().get().toFuture().join(),
        "which resolves as restricted (feet block barred)");
  }

  @Test
  void noBreakCheckerLeavesTheMoveUnrestricted() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    Movement<MinecraftStepPayload> tunnel =
        TestMovements.from(walker, player, world, new Cell(0, 1, 0)).to(new Cell(1, 1, 0));
    assertNotNull(tunnel);
    assertNull(tunnel.restricted(), "no integration checker → no future allocated");
  }
}
