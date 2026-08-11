/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.PlatformTransition;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.joml.Vector3i;

/**
 * A {@link PlatformTransition} with Paper's region and position types already bound — a Bukkit
 * {@link Location} destination and a {@link WorldRegion} over Bukkit {@link World}s. Integrations
 * return these from {@link PaperOdysseySearchModifier#computeTransitions}, so they never have to
 * spell out (or echo) the full generic signature. Build one with {@link #of}.
 */
public interface PaperTransition
    extends PlatformTransition<WorldRegion<World, Vector3i>, Location> {

  /**
   * A transition whose player-facing {@link #time() time} equals its {@code cost}.
   *
   * @param origin the origin region the agent must reach
   * @param destination the arrival location
   * @param cost the traversal cost/time in seconds
   * @param payload the step payload (e.g. {@link MinecraftStepPayload#command(String)})
   * @return the transition
   */
  static PaperTransition of(
      WorldRegion<World, Vector3i> origin,
      Location destination,
      double cost,
      MinecraftStepPayload payload) {
    return of(origin, destination, cost, cost, payload);
  }

  /**
   * A transition whose danger/penalty {@code cost} and real {@code time} differ.
   *
   * @param origin the origin region the agent must reach
   * @param destination the arrival location
   * @param cost the search cost in seconds (may include penalties)
   * @param time the real traversal time in seconds (player-facing)
   * @param payload the step payload
   * @return the transition
   */
  static PaperTransition of(
      WorldRegion<World, Vector3i> origin,
      Location destination,
      double cost,
      double time,
      MinecraftStepPayload payload) {
    return new Simple(origin, destination, cost, time, payload);
  }

  /**
   * A "wormhole" transition usable from anywhere in the player's current world: reach anywhere in
   * that world, run {@code command}, arrive at {@code destination}. The origin is the whole current
   * world, so the search can offer it immediately and prompt the command right away (e.g. {@code
   * /home}, {@code /town spawn X}). This is the common shape for command-driven teleports.
   *
   * @param player the player being routed (its current world becomes the origin)
   * @param destination the location the command lands the player at
   * @param cost the traversal cost/time in seconds
   * @param command the command to run, including the leading slash
   * @return the transition
   */
  static PaperTransition command(Player player, Location destination, double cost, String command) {
    return of(
        WholeWorldRegion.of(player.getWorld()),
        destination,
        cost,
        MinecraftStepPayload.command(command));
  }

  /**
   * The canonical {@link PaperTransition} implementation returned by {@link PaperTransition#of}.
   */
  record Simple(
      WorldRegion<World, Vector3i> origin,
      Location destination,
      double cost,
      double time,
      MinecraftStepPayload payload)
      implements PaperTransition {}
}
