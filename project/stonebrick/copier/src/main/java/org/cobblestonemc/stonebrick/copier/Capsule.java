/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.copier;

/**
 * The chunks within a radius of a line segment: the shape a route's terrain actually is.
 *
 * <p>A circle would be the same idea and quadratically wasteful on the routes that matter — a long
 * route needs an enclosing circle whose radius is half its length — while a capsule hugs the
 * corridor and collapses to a circle when the segment is short. The benchmark records what a
 * scenario needs in exactly this shape, so the copier takes it in exactly this shape.
 */
final class Capsule {

  private Capsule() {}

  /**
   * Returns whether a chunk lies within {@code radius} chunks of the segment.
   *
   * @param x1 one end's chunk X
   * @param z1 one end's chunk Z
   * @param x2 the other end's chunk X
   * @param z2 the other end's chunk Z
   * @param radius the radius in chunks
   * @param chunkX the chunk X to test
   * @param chunkZ the chunk Z to test
   * @return {@code true} if within the capsule
   */
  static boolean contains(int x1, int z1, int x2, int z2, int radius, int chunkX, int chunkZ) {
    double dx = (double) x2 - x1;
    double dz = (double) z2 - z1;
    double lengthSquared = dx * dx + dz * dz;
    double t =
        lengthSquared == 0
            ? 0
            : Math.clamp(((chunkX - x1) * dx + (chunkZ - z1) * dz) / lengthSquared, 0.0, 1.0);
    return Math.hypot(chunkX - (x1 + t * dx), chunkZ - (z1 + t * dz)) <= radius;
  }
}
