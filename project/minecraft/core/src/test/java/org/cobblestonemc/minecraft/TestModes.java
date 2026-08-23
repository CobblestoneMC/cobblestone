/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;

/**
 * Test helper: runs a mode's immediate step and indexes the resulting movements by destination
 * cell.
 */
public final class TestModes {

  private TestModes() {}

  public static Map<Cell, Movement<MinecraftStepPayload>> from(
      MinecraftMode<CobblestonePlayer> mode,
      CobblestonePlayer player,
      TestWorld world,
      Cell origin,
      TraversalState state) {
    FutureOr<java.util.Collection<Movement<MinecraftStepPayload>>> result =
        // A test world serves every block from memory, so the destination — which only steers
        // chunk read-ahead — makes no difference here.
        mode.step(player, origin, world, state, origin);
    assertTrue(result.isImmediate(), "test worlds serve blocks immediately");
    Map<Cell, Movement<MinecraftStepPayload>> byCell = new HashMap<>();
    for (Movement<MinecraftStepPayload> movement : result.value()) {
      byCell.put(movement.cell(), movement);
    }
    return byCell;
  }

  public static Map<Cell, Movement<MinecraftStepPayload>> from(
      MinecraftMode<CobblestonePlayer> mode,
      CobblestonePlayer player,
      TestWorld world,
      Cell origin) {
    return from(mode, player, world, origin, TraversalState.DEFAULT);
  }
}
