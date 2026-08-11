/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;

/**
 * Test helper: runs a mode's immediate step and indexes the resulting movements by destination
 * cell.
 */
public final class TestModes {

  private TestModes() {}

  public static Map<Cell, Movement<MinecraftStepPayload>> from(
      MinecraftMode<OdysseyPlayer> mode,
      OdysseyPlayer player,
      TestWorld world,
      Cell origin,
      TraversalState state) {
    FutureOr<java.util.Collection<Movement<MinecraftStepPayload>>> result =
        mode.step(player, origin, world, state);
    assertTrue(result.isImmediate(), "test worlds serve blocks immediately");
    Map<Cell, Movement<MinecraftStepPayload>> byCell = new HashMap<>();
    for (Movement<MinecraftStepPayload> movement : result.value()) {
      byCell.put(movement.cell(), movement);
    }
    return byCell;
  }

  public static Map<Cell, Movement<MinecraftStepPayload>> from(
      MinecraftMode<OdysseyPlayer> mode, OdysseyPlayer player, TestWorld world, Cell origin) {
    return from(mode, player, world, origin, TraversalState.DEFAULT);
  }
}
