/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import net.whimxiqal.odyssey.api.SearchSettings;

import java.util.Set;

public record MinecraftSearchSettings(SearchSettings settings, Set<MinecraftStepType> excludedModes,
                                      Set<String> excludedWorlds, Set<String> excludedDimensions) {

  public static MinecraftSearchSettings defaults() {
    return new MinecraftSearchSettings(SearchSettings.defaults(), Set.of(), Set.of(), Set.of());
  }

}
