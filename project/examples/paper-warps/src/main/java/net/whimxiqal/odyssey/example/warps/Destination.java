/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.example.warps;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A named target location that {@link Portal}s point at. A first-class, editable concept: moving a
 * destination (re-creating it) moves every portal linked to it. Only the world's key is stored; the
 * live {@link World} is resolved on use.
 *
 * @param name the destination name (unique, lower-case)
 * @param world the world key (e.g. {@code minecraft:the_nether})
 * @param x the x
 * @param y the y
 * @param z the z
 * @param yaw the facing yaw
 * @param pitch the facing pitch
 */
record Destination(String name, String world, double x, double y, double z, float yaw, float pitch) {

  /** Builds a Bukkit location in the given (already-resolved) world. */
  Location toLocation(World resolved) {
    return new Location(resolved, x, y, z, yaw, pitch);
  }
}
