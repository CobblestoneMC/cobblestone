/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.api.NavigatorSettings;

public interface NavigatorFactory {

  /**
   * Creates a navigator.
   *
   * @param player the player to guide
   * @param path the path to follow
   * @param settings per-trip appearance overrides; anything unset falls back to configured defaults
   * @return the navigator
   */
  Navigator<Location> create(
      Player player, Path<Location, MinecraftStepPayload> path, NavigatorSettings settings);
}
