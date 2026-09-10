/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftMode;
import org.cobblestonemc.minecraft.TestPlayer;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class MinecraftModesTest {

  private static List<String> modeNamesFor(CobblestonePlayer player) {
    return MinecraftModes.forPlayer(player, Set.of()).stream()
        .map(mode -> mode.getClass().getSimpleName())
        .toList();
  }

  private static List<String> modeNamesFor(
      CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    return MinecraftModes.forPlayer(player, excluded).stream()
        .map(mode -> mode.getClass().getSimpleName())
        .toList();
  }

  @Test
  void aWalkerCarriesTheGroundModes() {
    List<String> modes = modeNamesFor(TestPlayer.walker());
    assertTrue(modes.contains("WalkMode"));
    assertTrue(modes.contains("FallMode"));
    assertTrue(modes.contains("ClimbMode"));
    assertFalse(modes.contains("FlyMode"));
  }

  /**
   * Flight is cheaper per block than walking, falling and climbing and reaches a superset of their
   * cells, so a player who can fly gains nothing from carrying them — and each one costs a fetch of
   * its whole neighborhood on every expansion.
   */
  @Test
  void aFlierDropsTheModesFlightDominates() {
    List<String> modes = modeNamesFor(TestPlayer.create(true, false, false, true));
    assertTrue(modes.contains("FlyMode"));
    assertFalse(modes.contains("WalkMode"), "flight is cheaper than walking and reaches more");
    assertFalse(modes.contains("FallMode"), "a flier never needs to fall");
    assertFalse(modes.contains("ClimbMode"), "flight is cheaper than climbing");
  }

  @Test
  void aFlierKeepsTheModesFlightCannotReplace() {
    List<String> modes = modeNamesFor(TestPlayer.create(true, false, false, true));
    assertTrue(modes.contains("SwimMode"), "flight cannot enter water");
    assertTrue(modes.contains("DoorMode"), "a flier still needs a closed door opened");
    assertTrue(modes.contains("MineMode"), "a flier sealed in a room still needs a way out");
  }

  /** {@code -no-fly} means there is no flight to dominate anything, so the ground modes return. */
  @Test
  void excludingFlightBringsTheGroundModesBack() {
    List<String> modes =
        modeNamesFor(TestPlayer.create(true, false, false, true), Set.of(MinecraftStepType.FLY));
    assertFalse(modes.contains("FlyMode"));
    assertTrue(modes.contains("WalkMode"));
    assertTrue(modes.contains("FallMode"));
    assertTrue(modes.contains("ClimbMode"));
  }

  /**
   * Gliding is not flight — a glider cannot hover or gain height — so it dominates nothing and the
   * ground modes must stay.
   */
  @Test
  void aGliderKeepsTheGroundModes() {
    List<String> modes = modeNamesFor(TestPlayer.glider());
    assertTrue(modes.contains("FlyMode"), "the glider is modelled as a 1-tall flier");
    assertTrue(modes.contains("WalkMode"));
    assertTrue(modes.contains("FallMode"));
  }

  @Test
  void modeListsAreNeverEmpty() {
    for (CobblestonePlayer player :
        List.of(TestPlayer.walker(), TestPlayer.create(true, true, false, true))) {
      List<MinecraftMode<CobblestonePlayer>> modes = MinecraftModes.forPlayer(player, Set.of());
      assertFalse(modes.isEmpty());
    }
  }
}
