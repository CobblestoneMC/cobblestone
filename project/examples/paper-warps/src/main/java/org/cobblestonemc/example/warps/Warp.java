/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.example.warps;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A command warp: {@code /warp <name>} teleports the player here from anywhere. Unlike a {@link
 * Portal}, a warp bakes in its own location at creation time (it does not reference a {@link
 * Destination}). Surfaced to navigation as a {@code COMMAND} transition whose origin is the whole
 * current world, so Cobblestone can offer it immediately and prompt the player to type the command.
 *
 * @param name the warp name (unique, lower-case)
 * @param world the world key of the warp's location
 * @param x the x
 * @param y the y
 * @param z the z
 * @param yaw the facing yaw
 * @param pitch the facing pitch
 * @param cost the traversal cost/time in seconds charged for taking this warp
 */
record Warp(
    String name, String world, double x, double y, double z, float yaw, float pitch, double cost) {

  /** Builds a Bukkit location in the given (already-resolved) world. */
  Location toLocation(World resolved) {
    return new Location(resolved, x, y, z, yaw, pitch);
  }

  /** Returns a copy with the traversal cost set to the given seconds. */
  Warp withCost(double newCost) {
    return new Warp(name, world, x, y, z, yaw, pitch, newCost);
  }
}
