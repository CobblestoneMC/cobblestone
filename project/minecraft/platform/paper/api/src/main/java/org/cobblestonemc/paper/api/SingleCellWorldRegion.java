/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.api;

import java.text.NumberFormat;
import org.bukkit.Location;
import org.bukkit.World;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.joml.Vector3i;

/** A region covering exactly one block cell in one world. */
public class SingleCellWorldRegion implements WorldRegion<World, Vector3i> {

  private final World world;
  private final Vector3i vector;

  /**
   * A single-cell region at the given location's block.
   *
   * @param location the location whose block cell the region covers
   * @return the region
   */
  public static SingleCellWorldRegion of(Location location) {
    return new SingleCellWorldRegion(location);
  }

  private SingleCellWorldRegion(Location location) {
    this.world = location.getWorld();
    this.vector = location.toVector().toVector3i();
  }

  @Override
  public World world() {
    return world;
  }

  @Override
  public boolean contains(Vector3i vector) {
    return this.vector.equals(vector);
  }

  @Override
  public Vector3i nearestBoundaryLocation(Vector3i vector) {
    return this.vector;
  }

  @Override
  public String toString() {
    return String.format(
        "Cell[%s (%s)]", vector.toString(NumberFormat.getIntegerInstance()), world.getKey());
  }
}
