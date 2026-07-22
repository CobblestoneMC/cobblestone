/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Vector3i;

public final class BoxWorldRegion implements WorldRegion<World, Vector3i> {

  private final World world;
  private final int minX;
  private final int minY;
  private final int minZ;
  private final int maxX;
  private final int maxY;
  private final int maxZ;

  static public BoxWorldRegion of(Location corner1, Location corner2) {
    if (!corner1.getWorld().equals(corner2.getWorld())) {
      throw new IllegalArgumentException("Locations must be in the same world");
    }
    return new BoxWorldRegion(corner1.getWorld(), corner1.toVector().toVector3i(), corner2.toVector().toVector3i());
  }

  private BoxWorldRegion(World world, Vector3i corner1, Vector3i corner2) {
    this.world = world;
    this.minX = Math.min(corner1.x(), corner2.x());
    this.minY = Math.min(corner1.y(), corner2.y());
    this.minZ = Math.min(corner1.z(), corner2.z());
    this.maxX = Math.max(corner1.x(), corner2.x());
    this.maxY = Math.max(corner1.y(), corner2.y());
    this.maxZ = Math.max(corner1.z(), corner2.z());
  }

  @Override
  public World world() {
    return world;
  }

  @Override
  public boolean contains(Vector3i vector) {
    return vector.x() >= minX && vector.x() <= maxX
        && vector.y() >= minY && vector.y() <= maxY
        && vector.z() >= minZ && vector.z() <= maxZ;
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
}
