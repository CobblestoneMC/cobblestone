/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.example.warps;

/**
 * An auto-teleport pad: an axis-aligned box entrance (one or more blocks) that sends any player who
 * steps inside to a named {@link Destination}. Because it references the destination by name, editing
 * that destination moves every portal linked to it. Surfaced to navigation as a {@code PORTAL}
 * transition (walked into, not commanded), which exercises Odyssey's region-origin logic.
 *
 * @param name the portal name (unique, lower-case)
 * @param world the entrance box's world key
 * @param minX the box minimum x (inclusive)
 * @param minY the box minimum y (inclusive)
 * @param minZ the box minimum z (inclusive)
 * @param maxX the box maximum x (inclusive)
 * @param maxY the box maximum y (inclusive)
 * @param maxZ the box maximum z (inclusive)
 * @param destination the name of the {@link Destination} this portal sends players to
 * @param cost the traversal cost/time in seconds
 */
record Portal(
    String name, String world,
    int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
    String destination, double cost) {

  /** Whether the given block coordinates fall inside the entrance box. */
  boolean contains(String worldKey, int x, int y, int z) {
    return world.equals(worldKey)
        && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }

  /** Returns a copy with the traversal cost set to the given seconds. */
  Portal withCost(double newCost) {
    return new Portal(name, world, minX, minY, minZ, maxX, maxY, maxZ, destination, newCost);
  }
}
