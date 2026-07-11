/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.OdysseyApi;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.TransitionProvider;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The Paper-flavored developer entry point, registered in Bukkit's service manager by the Odyssey
 * plugin. It lets other Paper plugins request navigation in native terms ({@link Player},
 * {@link Location}) without touching Odyssey's generic core types.
 */
public interface PaperOdysseyApi {

  /**
   * Navigates a player toward a block location.
   *
   * @param player the player to guide
   * @param destination the target location
   * @return a handle to the in-flight search
   */
  SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld> navigatePlayer(
      Player player, Location destination);

  /**
   * Navigates a player toward a (possibly multi-region) destination.
   *
   * @param player the player to guide
   * @param destination the destination
   * @return a handle to the in-flight search
   */
  SearchHandle<MinecraftStepType, MinecraftInstruction, MinecraftWorld> navigatePlayer(
      Player player, Destination<MinecraftWorld> destination);

  /**
   * Registers a source of transitions (custom portals/teleports) available to searches.
   *
   * @param provider the transition provider
   */
  void registerTransitionProvider(TransitionProvider provider);

  /**
   * Returns the underlying generic API, for advanced use.
   *
   * @return the core API
   */
  OdysseyApi core();
}
