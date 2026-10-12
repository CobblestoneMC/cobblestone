/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.Map;
import java.util.OptionalInt;
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
 * <p><b>It never waits for a chunk.</b> It reads only through the view a solve binds it to with
 * {@link #scopedForSolve}, and only chunks that view already holds — which, for the cells a search
 * expands, it always does. A column whose chunk is not in hand is simply not known yet: none of its
 * cells count as open, and the nearest boundary falls back to a guess that reads nothing (see
 * {@link #nearestBoundaryCell}). Unbound, as it is before a solve starts, it knows nothing. Each
 * column's roof is computed once per bound region and remembered.
 */
public final class SurfaceRegion implements DomainRegion<MinecraftWorld> {

  private final MinecraftWorld world;
  private final @Nullable MinecraftWorld readWorld;
  private final @Nullable Column column;
  private final Map<Long, Integer> roofs = new ConcurrentHashMap<>();

  private SurfaceRegion(
      MinecraftWorld world, @Nullable MinecraftWorld readWorld, @Nullable Column column) {
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
    return new SurfaceRegion(world, null, null);
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
    return new SurfaceRegion(world, null, new Column(x, z));
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
    OptionalInt roof = roof(cell.x(), cell.z());
    return roof.isPresent() && cell.y() > roof.getAsInt();
  }

  /**
   * The open cell just above the roof of {@code from}'s column — or of the region's own column,
   * once {@code from} is in it. From outside a single-column region, it is the column itself at
   * {@code from}'s height: that steers the search across to it without reading a chunk that may be
   * far away, and the roof takes over once the search arrives. Where the roof is not known yet,
   * {@code from} itself, which claims nothing about the terrain.
   */
  @Override
  public Cell nearestBoundaryCell(Cell from) {
    if (column != null && (from.x() != column.x() || from.z() != column.z())) {
      return new Cell(column.x(), from.y(), column.z());
    }
    OptionalInt roof = roof(from.x(), from.z());
    if (roof.isEmpty() || from.y() > roof.getAsInt()) {
      return from; // already out in the open, or no way to tell yet
    }
    return new Cell(from.x(), roof.getAsInt() + 1, from.z());
  }

  @Override
  public DomainRegion<MinecraftWorld> scopedForSolve(MinecraftWorld readDomain) {
    return new SurfaceRegion(world, readDomain, column);
  }

  /**
   * The y of the highest non-passable block in the column, or one below the world floor if there is
   * none — a cell is open to the sky exactly when it is above this — or empty if the column's chunk
   * is not in hand.
   */
  private OptionalInt roof(int x, int z) {
    long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
    Integer known = roofs.get(key);
    if (known != null) {
      return OptionalInt.of(known);
    }
    MinecraftChunk chunk = chunkInHand(x, z);
    if (chunk == null) {
      return OptionalInt.empty();
    }
    int roof = scan(chunk, x & 15, z & 15);
    roofs.put(key, roof);
    return OptionalInt.of(roof);
  }

  private @Nullable MinecraftChunk chunkInHand(int x, int z) {
    if (readWorld == null) {
      return null;
    }
    Cell probe = new Cell(x, world.maxY(), z);
    FutureOr<MinecraftChunk> chunk = readWorld.chunkAt(probe, probe);
    return chunk.isImmediate() ? chunk.value() : null;
  }

  private int scan(MinecraftChunk chunk, int localX, int localZ) {
    for (int y = world.maxY(); y >= world.minY(); y--) {
      if (!chunk.block(localX, y, localZ).isPassable()) {
        return y;
      }
    }
    return world.minY() - 1;
  }

  @Override
  public String toString() {
    return column == null
        ? "Surface[" + world.key() + "]"
        : "Surface[" + column.x() + ", " + column.z() + " (" + world.key() + ")]";
  }

  private record Column(int x, int z) {}
}
