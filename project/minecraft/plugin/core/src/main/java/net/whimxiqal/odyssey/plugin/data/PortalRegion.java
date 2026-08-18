/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

/**
 * An axis-aligned block-coordinate box in one world, inclusive of both corners — a portal's frame.
 * A portal's <b>anchor</b> is {@code (world, minX, minY, minZ)}: stable identity for the caches
 * that key on it.
 *
 * @param world the world's namespaced key
 * @param minX inclusive minimum x
 * @param minY inclusive minimum y
 * @param minZ inclusive minimum z
 * @param maxX inclusive maximum x
 * @param maxY inclusive maximum y
 * @param maxZ inclusive maximum z
 */
public record PortalRegion(
    String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

  /** The world-space x of the region's horizontal center (block centers). */
  public double centerX() {
    return (minX + maxX) / 2.0 + 0.5;
  }

  /** The world-space z of the region's horizontal center (block centers). */
  public double centerZ() {
    return (minZ + maxZ) / 2.0 + 0.5;
  }

  /** The y a normalized exit lands at: the bottom of the portal. */
  public int groundY() {
    return minY;
  }

  /** Horizontal distance from a world-space point to this region's center. */
  public double horizontalDistanceTo(double x, double z) {
    double dx = x - centerX();
    double dz = z - centerZ();
    return Math.sqrt(dx * dx + dz * dz);
  }

  /** Whether {@code other} shares this region's anchor (same world + minimum corner). */
  public boolean sameAnchor(PortalRegion other) {
    return world.equals(other.world)
        && minX == other.minX
        && minY == other.minY
        && minZ == other.minZ;
  }
}
