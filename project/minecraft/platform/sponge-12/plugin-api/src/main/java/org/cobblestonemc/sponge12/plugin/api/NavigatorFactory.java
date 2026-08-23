/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin.api;

import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/** Builds a {@link Navigator} (a display strategy for a guided trip) for one player and path. */
public interface NavigatorFactory {

  /**
   * Creates a navigator.
   *
   * @param player the player to guide
   * @param path the path to follow
   * @param settings per-trip appearance overrides; anything unset falls back to configured defaults
   * @return the navigator
   */
  Navigator<ServerLocation> create(
      ServerPlayer player,
      Path<ServerLocation, MinecraftStepPayload> path,
      NavigatorSettings settings);
}
