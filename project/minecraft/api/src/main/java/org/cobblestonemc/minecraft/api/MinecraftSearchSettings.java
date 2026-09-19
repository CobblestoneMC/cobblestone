/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

import java.util.Set;
import org.cobblestonemc.api.SearchSettings;

/**
 * Search settings for a Minecraft search: the platform-independent {@link SearchSettings} plus
 * Minecraft-specific exclusions.
 *
 * @param settings the platform-independent search settings
 * @param excludedModes step types the route may not use
 * @param excludedWorlds keys of worlds the route may not pass through (e.g. {@code
 *     "minecraft:the_nether"})
 * @param excludedDimensions lower-case dimension types the route may not pass through ({@code
 *     overworld}, {@code nether}, {@code end} or {@code custom})
 */
public record MinecraftSearchSettings(
    SearchSettings settings,
    Set<MinecraftStepType> excludedModes,
    Set<String> excludedWorlds,
    Set<String> excludedDimensions) {

  /**
   * The default settings, with nothing excluded.
   *
   * @return the default settings
   */
  public static MinecraftSearchSettings defaults() {
    return new MinecraftSearchSettings(SearchSettings.defaults(), Set.of(), Set.of(), Set.of());
  }
}
