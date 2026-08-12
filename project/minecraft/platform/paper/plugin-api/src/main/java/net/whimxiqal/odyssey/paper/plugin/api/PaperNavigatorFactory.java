/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface PaperNavigatorFactory {

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
