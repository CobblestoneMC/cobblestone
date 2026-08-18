/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.api;

import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.PlatformTransition;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * A {@link PlatformTransition} with Sponge's region and position types already bound — a {@link
 * ServerLocation} destination and a {@link WorldRegion} over {@link ServerWorld}s. Integrations
 * return these from {@link SearchModificationService#computeTransitions}, so they never have to
 * spell out (or echo) the full generic signature. Build one with {@link #of}.
 */
public interface Transition
    extends PlatformTransition<WorldRegion<ServerWorld, Vector3i>, ServerLocation> {

  /**
   * A transition whose player-facing {@link #time() time} equals its {@code cost}.
   *
   * @param origin the origin region the agent must reach
   * @param destination the arrival location
   * @param cost the traversal cost/time in seconds
   * @param payload the step payload
   * @return the transition
   */
  static Transition of(
      WorldRegion<ServerWorld, Vector3i> origin,
      ServerLocation destination,
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
  static Transition of(
      WorldRegion<ServerWorld, Vector3i> origin,
      ServerLocation destination,
      double cost,
      double time,
      MinecraftStepPayload payload) {
    return new Simple(origin, destination, cost, time, payload);
  }

  /**
   * A "wormhole" transition usable from anywhere in the player's current world: reach anywhere in
   * that world, run {@code command}, arrive at {@code destination}.
   *
   * @param player the player being routed (its current world becomes the origin)
   * @param destination the location the command lands the player at
   * @param cost the traversal cost/time in seconds
   * @param command the command to run, including the leading slash
   * @return the transition
   */
  static Transition command(
      ServerPlayer player, ServerLocation destination, double cost, String command) {
    return of(
        WholeWorldRegion.of(player.serverLocation().world()),
        destination,
        cost,
        MinecraftStepPayload.command(command));
  }

  /** The canonical {@link Transition} implementation returned by {@link Transition#of}. */
  record Simple(
      WorldRegion<ServerWorld, Vector3i> origin,
      ServerLocation destination,
      double cost,
      double time,
      MinecraftStepPayload payload)
      implements Transition {}
}
