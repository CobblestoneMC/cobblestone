/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

import java.util.Set;
import org.cobblestonemc.api.SearchSettings;

public record MinecraftSearchSettings(
    SearchSettings settings,
    Set<MinecraftStepType> excludedModes,
    Set<String> excludedWorlds,
    Set<String> excludedDimensions) {

  public static MinecraftSearchSettings defaults() {
    return new MinecraftSearchSettings(SearchSettings.defaults(), Set.of(), Set.of(), Set.of());
  }
}
