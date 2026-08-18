/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.api;

import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/** A region covering exactly one block cell in one world. */
public final class SingleCellWorldRegion implements WorldRegion<ServerWorld, Vector3i> {

  private final ServerWorld world;
  private final Vector3i vector;

  /**
   * A single-cell region at the given location's block.
   *
   * @param location the location whose block cell the region covers
   * @return the region
   */
  public static SingleCellWorldRegion of(ServerLocation location) {
    return new SingleCellWorldRegion(location);
  }

  private SingleCellWorldRegion(ServerLocation location) {
    this.world = location.world();
    this.vector = location.blockPosition();
  }

  @Override
  public ServerWorld world() {
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
    return "Cell[" + vector + " (" + world.key().asString() + ")]";
  }
}
