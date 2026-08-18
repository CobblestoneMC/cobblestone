/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.api;

import java.util.Objects;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * An axis-aligned box of block cells in one world, inclusive of both corners — the region type most
 * transitions use for their origin (a portal frame, a warp pad, a trigger volume). Holds a live
 * {@link ServerWorld} reference; build it fresh from current worlds rather than caching it across
 * reloads.
 */
public final class BoxWorldRegion implements WorldRegion<ServerWorld, Vector3i> {

  private final ServerWorld world;
  private final int minX;
  private final int minY;
  private final int minZ;
  private final int maxX;
  private final int maxY;
  private final int maxZ;

  /**
   * A box spanning the two corner locations (inclusive). Order does not matter.
   *
   * @param corner1 one corner
   * @param corner2 the opposite corner (must be in the same world)
   * @return the region
   * @throws IllegalArgumentException if the corners are in different worlds
   */
  public static BoxWorldRegion of(ServerLocation corner1, ServerLocation corner2) {
    ServerWorld world = Objects.requireNonNull(corner1.world(), "corner1 has no world");
    Objects.requireNonNull(corner2.world(), "corner2 has no world");
    if (!world.key().equals(corner2.world().key())) {
      throw new IllegalArgumentException("Locations must be in the same world");
    }
    return new BoxWorldRegion(world, corner1.blockPosition(), corner2.blockPosition());
  }

  /**
   * A single-cell box at the given location's block.
   *
   * @param location the location whose block cell the region covers
   * @return the region
   */
  public static BoxWorldRegion of(ServerLocation location) {
    return around(location, 0);
  }

  /**
   * A cube of {@code (2·radius + 1)} cells per side, centerd on the given location's block.
   *
   * @param center the center location
   * @param radius the number of cells to extend in each direction (0 is a single cell)
   * @return the region
   * @throws IllegalArgumentException if {@code radius} is negative
   */
  public static BoxWorldRegion around(ServerLocation center, int radius) {
    ServerWorld world = Objects.requireNonNull(center.world(), "center has no world");
    if (radius < 0) {
      throw new IllegalArgumentException("radius must be >= 0");
    }
    Vector3i block = center.blockPosition();
    return new BoxWorldRegion(
        world,
        new Vector3i(block.x() - radius, block.y() - radius, block.z() - radius),
        new Vector3i(block.x() + radius, block.y() + radius, block.z() + radius));
  }

  private BoxWorldRegion(ServerWorld world, Vector3i corner1, Vector3i corner2) {
    this.world = world;
    this.minX = Math.min(corner1.x(), corner2.x());
    this.minY = Math.min(corner1.y(), corner2.y());
    this.minZ = Math.min(corner1.z(), corner2.z());
    this.maxX = Math.max(corner1.x(), corner2.x());
    this.maxY = Math.max(corner1.y(), corner2.y());
    this.maxZ = Math.max(corner1.z(), corner2.z());
  }

  @Override
  public ServerWorld world() {
    return world;
  }

  @Override
  public boolean contains(Vector3i vector) {
    return vector.x() >= minX
        && vector.x() <= maxX
        && vector.y() >= minY
        && vector.y() <= maxY
        && vector.z() >= minZ
        && vector.z() <= maxZ;
  }

  @Override
  public Vector3i nearestBoundaryLocation(Vector3i vector) {
    if (contains(vector)) {
      return vector;
    }
    int x = Math.clamp(vector.x(), minX, maxX);
    int y = Math.clamp(vector.y(), minY, maxY);
    int z = Math.clamp(vector.z(), minZ, maxZ);
    return new Vector3i(x, y, z);
  }

  @Override
  public String toString() {
    return "BoxWorldRegion{world="
        + world.key().asString()
        + ", ["
        + minX
        + ", "
        + minY
        + ", "
        + minZ
        + "], ["
        + maxX
        + ", "
        + maxY
        + ", "
        + maxZ
        + "]}";
  }
}
