/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import java.text.NumberFormat;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.joml.Vector3i;

public class SingleCellWorldRegion implements WorldRegion<World, Vector3i> {

  private final Location location;

  public static SingleCellWorldRegion of(Location location) {
    return new SingleCellWorldRegion(location);
  }

  private SingleCellWorldRegion(Location location) {
    this.location = location;
  }

  @Override
  public World world() {
    return location.getWorld();
  }

  @Override
  public boolean contains(Vector3i vector) {
    // Match within one block (the 3x3x3 around the target) so navigation completes even when the
    // exact destination block is not itself standable.
    Vector3i cell = location.toVector().toVector3i();
    return Math.abs(vector.x - cell.x) <= 1
        && Math.abs(vector.y - cell.y) <= 1
        && Math.abs(vector.z - cell.z) <= 1;
  }

  @Override
  public Vector3i nearestBoundaryLocation(Vector3i vector) {
    Vector3i cell = location.toVector().toVector3i();
    return new Vector3i(
        Math.clamp(vector.x, cell.x - 1, cell.x + 1),
        Math.clamp(vector.y, cell.y - 1, cell.y + 1),
        Math.clamp(vector.z, cell.z - 1, cell.z + 1));
  }

  @Override
  public String toString() {
    return String.format(
        "Cell[%s (%s)]",
        location.toVector().toVector3i().toString(NumberFormat.getIntegerInstance()),
        location.getWorld().getKey());
  }
}
