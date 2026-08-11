/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.minecraft.*;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.OdysseyPlayer;
import org.junit.jupiter.api.Test;

class MineModeTest {

  private final MineMode<OdysseyPlayer> mine = new MineMode<>();

  private static TestWorld wallTo(int wallX, MinecraftBlock feet,
                                  MinecraftBlock head) {
    return TestWorld.builder("w")
        .floor(0, -1, -1, 2, 1, TestBlocks.solid())
        .set(wallX, 1, 0, feet)
        .set(wallX, 2, 0, head)
        .build();
  }

  @Test
  void tunnelsThroughBreakableWall() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    Map<Cell, Movement<MinecraftStepPayload>> moves =
        TestModes.from(mine, TestPlayer.walker(), world, new Cell(0, 1, 0));

    Movement<MinecraftStepPayload> dig = moves.get(new Cell(1, 1, 0));
    assertEquals(MinecraftStepType.MINE, dig.payload().stepType());
    // break feet (2s) + head (2s) + a walk step
    assertEquals(2.0 + 2.0 + MovementCosts.WALK, dig.cost(), 1e-9);
  }

  @Test
  void willNotMineUnbreakableBlocks() {
    TestWorld world = wallTo(1, TestBlocks.
            bedrock(), TestBlocks.solid(2.0));
    assertFalse(TestModes.from(mine, TestPlayer.walker(), world, new Cell(0, 1, 0))
        .containsKey(new Cell(1, 1, 0)));
  }

  @Test
  void willNotMineWhenBreakingIsNotAllowed() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    OdysseyPlayer cannotBreak = TestPlayer.create(false, false, false, false);
    assertFalse(TestModes.from(mine, cannotBreak, world, new Cell(0, 1, 0))
        .containsKey(new Cell(1, 1, 0)));
  }

  @Test
  void injectedBreakCheckerTagsTheEdgeAsRestrictedOptimistically() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    // An integration forbids breaking the block at the wall's feet. The mode still emits the tunnel
    // move (optimistically), but tags it with a restricted future that resolves true — the search
    // drops the edge when it does.
    BreakChecker<OdysseyPlayer> forbidWall =
        (agent, cell, breakWorld, block) -> CompletableFuture.completedFuture(!cell.equals(new Cell(1, 1, 0)));
    MineMode<OdysseyPlayer> mode = new MineMode<>(forbidWall);

    Movement<MinecraftStepPayload> tunnel =
        TestModes.from(mode, TestPlayer.walker(), world, new Cell(0, 1, 0)).get(new Cell(1, 1, 0));
    assertNotNull(tunnel, "the move is emitted optimistically");
    assertNotNull(tunnel.restricted(), "and carries a breakability check");
    assertTrue(tunnel.restricted().join(), "which resolves as restricted (feet block barred)");
  }

  @Test
  void noBreakCheckerLeavesTheMoveUnrestricted() {
    TestWorld world = wallTo(1, TestBlocks.solid(2.0), TestBlocks.solid(2.0));
    Movement<MinecraftStepPayload> tunnel =
        TestModes.from(mine, TestPlayer.walker(), world, new Cell(0, 1, 0)).get(new Cell(1, 1, 0));
    assertNotNull(tunnel);
    assertNull(tunnel.restricted(), "no integration checker → no future allocated");
  }
}
