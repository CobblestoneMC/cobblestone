/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.whimxiqal.odyssey.minecraft.api.MinecraftMode;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import org.junit.jupiter.api.Test;

class MinecraftModesTest {

  private static Set<MinecraftStepType> stepTypes(boolean canFly, boolean hasBoat, Set<MinecraftStepType> excluded) {
    return MinecraftModes.forPlayer(TestPlayer.create(canFly, hasBoat, true), excluded).stream()
        .map(MinecraftMode::stepType)
        .collect(Collectors.toSet());
  }

  @Test
  void plainWalkerGetsTheGroundModesButNotFlyOrBoat() {
    Set<MinecraftStepType> types = stepTypes(false, false, Set.of());
    assertTrue(types.containsAll(EnumSet.of(
        MinecraftStepType.WALK, MinecraftStepType.FALL, MinecraftStepType.SWIM,
        MinecraftStepType.CLIMB, MinecraftStepType.OPEN_DOOR, MinecraftStepType.MINE,
        MinecraftStepType.HORSE)));
    assertFalse(types.contains(MinecraftStepType.FLY));
    assertFalse(types.contains(MinecraftStepType.BOAT));
  }

  @Test
  void flyingAndBoatingAreGatedByAbility() {
    assertTrue(stepTypes(true, false, Set.of()).contains(MinecraftStepType.FLY));
    assertTrue(stepTypes(false, true, Set.of()).contains(MinecraftStepType.BOAT));
  }

  @Test
  void excludedStepTypesAreRemoved() {
    Set<MinecraftStepType> types =
        stepTypes(true, true, Set.of(MinecraftStepType.FLY, MinecraftStepType.MINE));
    assertFalse(types.contains(MinecraftStepType.FLY));
    assertFalse(types.contains(MinecraftStepType.MINE));
    assertTrue(types.contains(MinecraftStepType.WALK));
  }
}
