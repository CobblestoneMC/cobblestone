/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.whimxiqal.odyssey.minecraft.api.MinecraftMode;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.OdysseyPlayer;

/**
 * Assembles the mode list for a search. Ability gating happens here (not inside the modes): flying
 * and boating are added only when the player can, while horse travel is always present but stays
 * dormant until the traversal state says the player is mounted.
 */
public final class MinecraftModes {

  private MinecraftModes() {
  }

  /**
   * Builds the modes available to {@code player}, minus any whose primary step type is excluded
   * (e.g. {@code -no-fly}).
   *
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @return the mode list
   */
  public static List<MinecraftMode<OdysseyPlayer>> forPlayer(
      OdysseyPlayer player, Set<MinecraftStepType> excluded) {
    List<MinecraftMode<OdysseyPlayer>> modes = new ArrayList<>();
    if (!excluded.contains(MinecraftStepType.WALK)) {
      modes.add(new WalkMode<>());
      modes.add(new DoorMode<>(!excluded.contains(MinecraftStepType.OPEN_DOOR)));
      if (!excluded.contains(MinecraftStepType.MINE)) {
        modes.add(new MineMode<>());
      }
    }
    if (!excluded.contains(MinecraftStepType.FALL)) {
      modes.add(new FallMode<>());
    }
    if (!excluded.contains(MinecraftStepType.SWIM)) {
      modes.add(new SwimMode<>());
    }
    if (!excluded.contains(MinecraftStepType.CLIMB)) {
      modes.add(new ClimbMode<>());
    }
    if (!excluded.contains(MinecraftStepType.HORSE)) {
      modes.add(new HorseMode<>());
    }
    if (!excluded.contains(MinecraftStepType.FLY) && player.canFly()) {
      modes.add(new FlyMode<>());
    }
    if (!excluded.contains(MinecraftStepType.BOAT) && player.hasBoatInInventory() || player.isInBoat()) {
      modes.add(new BoatMode<>());
    }
    return modes;
  }
}
