/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;

/**
 * A live world's chunk columns, for a {@link RegionGraph}.
 *
 * <p>Written against {@link MinecraftWorld} rather than any one platform's classes, so Paper and
 * Sponge both get it without a copy. Nothing is read up front: the graph asks for a column the
 * first time a search needs it, and the fine search parks until it lands.
 */
public final class WorldChunkColumns implements RegionGraph.ChunkColumns {

  private final MinecraftWorld world;
  private final Cell destination;
  private final Map<Long, FutureOr<MinecraftChunk>> columns = new ConcurrentHashMap<>();

  /**
   * Creates a source over a world.
   *
   * @param world the world to read
   * @param destination where the solve is heading, the chunk provider's hint for how hard to try
   */
  public WorldChunkColumns(MinecraftWorld world, Cell destination) {
    this.world = world;
    this.destination = destination;
  }

  @Override
  public FutureOr<MinecraftChunk> column(int chunkX, int chunkZ) {
    long key = ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    FutureOr<MinecraftChunk> known = columns.get(key);
    if (known != null) {
      // Remembered while in flight too, so a seam's five columns queue one read each, and so a
      // column asked about after its read landed answers at once, as the graph requires.
      return known;
    }
    Cell anchor =
        new Cell(
            chunkX * 16,
            Math.max(world.minY(), Math.min(world.maxY(), destination.y())),
            chunkZ * 16);
    FutureOr<MinecraftChunk> chunk = world.chunkAt(anchor, destination);
    // A read that fails is a column that cannot be known; left failed, it would park the search on
    // a future that is forever done and never usable.
    FutureOr<MinecraftChunk> settled =
        chunk.isImmediate() ? chunk : FutureOr.from(chunk.future().exceptionally(failure -> null));
    columns.put(key, settled);
    return settled;
  }

  @Override
  public int minY() {
    return world.minY();
  }

  @Override
  public int maxY() {
    return world.maxY();
  }
}
