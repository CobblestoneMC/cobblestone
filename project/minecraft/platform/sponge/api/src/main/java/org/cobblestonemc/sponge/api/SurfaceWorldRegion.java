/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.api;

import org.cobblestonemc.minecraft.api.WorldRegion;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Every cell in a world that is open to the sky — or, with {@link #column}, only those in one
 * column. Used to navigate "to the surface": the search succeeds at the first cell with nothing but
 * passable blocks above it, and steers straight up to get there.
 *
 * <p>The extent of this region depends on the blocks in the world, which only the search can read
 * safely (from its own chunk snapshots, off the server thread). So Cobblestone evaluates it itself,
 * and {@link #contains} and {@link #nearestBoundaryLocation} are not supported here. Holds only the
 * world's key and re-resolves the {@link ServerWorld} on demand, like {@link WholeWorldRegion}.
 */
public final class SurfaceWorldRegion implements WorldRegion<ServerWorld, Vector3i> {

  private final String worldKey;
  private final Vector3i column;

  private SurfaceWorldRegion(String worldKey, Vector3i column) {
    this.worldKey = worldKey;
    this.column = column;
  }

  /**
   * The surface anywhere in the given world.
   *
   * @param world the world
   * @return the region
   */
  public static SurfaceWorldRegion anywhere(ServerWorld world) {
    return new SurfaceWorldRegion(world.key().asString(), null);
  }

  /**
   * The surface of a single column of the given world.
   *
   * @param world the world
   * @param x the column's block x-coordinate
   * @param z the column's block z-coordinate
   * @return the region
   */
  public static SurfaceWorldRegion column(ServerWorld world, int x, int z) {
    return new SurfaceWorldRegion(world.key().asString(), new Vector3i(x, 0, z));
  }

  /**
   * Returns the column this region is confined to, as an {@code (x, 0, z)} vector, or {@code null}
   * if it spans the whole world.
   *
   * @return the column, or null
   */
  public Vector3i column() {
    return column;
  }

  @Override
  public ServerWorld world() {
    return Sponge.server().worldManager().world(ResourceKey.resolve(worldKey)).orElse(null);
  }

  /**
   * Not supported: whether a cell is open to the sky is decided by the search, against its own
   * chunk snapshots.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public boolean contains(Vector3i vector) {
    throw new UnsupportedOperationException("the surface is evaluated by the search");
  }

  /**
   * Not supported: the surface above a cell is found by the search, against its own chunk
   * snapshots.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public Vector3i nearestBoundaryLocation(Vector3i vector) {
    throw new UnsupportedOperationException("the surface is evaluated by the search");
  }

  @Override
  public String toString() {
    return column == null
        ? "Surface[" + worldKey + "]"
        : "Surface[" + column.x() + ", " + column.z() + " (" + worldKey + ")]";
  }
}
