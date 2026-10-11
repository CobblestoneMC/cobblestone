/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.Cell;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.FutureOr;
import org.jetbrains.annotations.Nullable;

/**
 * Every cell open to the sky: nothing but {@linkplain MinecraftBlock#isPassable() passable} blocks
 * above it, all the way to the top of the world. Optionally confined to a single column, for "the
 * surface at x, z".
 *
 * <p>Unlike a box or a cell, the extent of this region depends on what is in the world, so it reads
 * blocks. Its nearest boundary cell is straight up: the open cell just above the column's highest
 * roofing block. That is not an admissible bound — a cave mouth a few blocks to the side can be
 * closer than the sky overhead — but it steers the search upwards, which is what "get me to the
 * surface" means, and the search still stops at the first open cell it reaches from any direction.
 *
 * <p>Water and leaves are not passable, so they count as a roof: a player at the bottom of the
 * ocean or under a canopy is not yet at the surface.
 *
 * <p>The region reads chunks through its world; a solve binds it to its own scoped view with {@link
 * #scopedForSolve}, so it reads the chunks the solve already holds. Each column's roof is computed
 * once per region and remembered.
 */
public final class SurfaceRegion implements DomainRegion<MinecraftWorld> {

  private final MinecraftWorld world;
  private final MinecraftWorld readWorld;
  private final @Nullable Column column;
  private final Map<Long, Integer> roofs = new ConcurrentHashMap<>();

  private SurfaceRegion(MinecraftWorld world, MinecraftWorld readWorld, @Nullable Column column) {
    this.world = world;
    this.readWorld = readWorld;
    this.column = column;
  }

  /**
   * Every cell in the world that is open to the sky.
   *
   * @param world the world
   * @return the region
   */
  public static SurfaceRegion anywhere(MinecraftWorld world) {
    return new SurfaceRegion(world, world, null);
  }

  /**
   * The cells of one column that are open to the sky.
   *
   * @param world the world
   * @param x the column's block x-coordinate
   * @param z the column's block z-coordinate
   * @return the region
   */
  public static SurfaceRegion column(MinecraftWorld world, int x, int z) {
    return new SurfaceRegion(world, world, new Column(x, z));
  }

  @Override
  public MinecraftWorld domain() {
    return world;
  }

  @Override
  public boolean contains(Cell cell) {
    if (column != null && (cell.x() != column.x() || cell.z() != column.z())) {
      return false;
    }
    return cell.y() >= roof(cell.x(), cell.z());
  }

  @Override
  public Cell nearestBoundaryCell(Cell from) {
    int x = column == null ? from.x() : column.x();
    int z = column == null ? from.z() : column.z();
    int roof = roof(x, z);
    if (from.x() == x && from.z() == z && from.y() >= roof) {
      return from; // already out in the open
    }
    return new Cell(x, roof + 1, z);
  }

  @Override
  public DomainRegion<MinecraftWorld> scopedForSolve(MinecraftWorld readDomain) {
    return new SurfaceRegion(world, readDomain, column);
  }

  /**
   * The y of the highest non-passable block in the column, or one below the world floor if there is
   * none. A cell is open to the sky exactly when it is at or above this.
   */
  private int roof(int x, int z) {
    long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
    Integer known = roofs.get(key);
    if (known != null) {
      return known;
    }
    // Not computeIfAbsent: the scan may wait on a chunk fetch, which must not hold a map bin.
    int roof = scan(x, z);
    roofs.put(key, roof);
    return roof;
  }

  private int scan(int x, int z) {
    Cell probe = new Cell(x, world.maxY(), z);
    MinecraftChunk chunk = await(readWorld.chunkAt(probe, probe));
    int localX = x & 15;
    int localZ = z & 15;
    for (int y = world.maxY(); y >= world.minY(); y--) {
      if (!chunk.block(localX, y, localZ).isPassable()) {
        return y;
      }
    }
    return world.minY() - 1;
  }

  /**
   * The chunk, waiting for it if it is not already in hand. Within a solve the column being asked
   * about is one the search has just expanded, so its chunk is cached and this does not wait.
   */
  private static MinecraftChunk await(FutureOr<MinecraftChunk> chunk) {
    return chunk.isImmediate() ? chunk.value() : chunk.toFuture().join();
  }

  @Override
  public String toString() {
    return column == null
        ? "Surface[" + world.key() + "]"
        : "Surface[" + column.x() + ", " + column.z() + " (" + world.key() + ")]";
  }

  private record Column(int x, int z) {}
}
