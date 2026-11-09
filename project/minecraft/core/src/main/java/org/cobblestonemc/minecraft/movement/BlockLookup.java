/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.ChunkSource;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.UnknownBlock;

/** Fetches the blocks an expansion reads into a {@link BlockView}, resolving each chunk once. */
final class BlockLookup {

  /** The box, around the expanding cell, that every expansion's reads fall within. */
  static final int VIEW_XZ_RADIUS = 2;

  static final int VIEW_DY_LOW = -2;
  static final int VIEW_DY_HIGH = 3;

  private BlockLookup() {}

  /** Fetches the given cells around {@code from}. Every cell must lie within the view box. */
  static FutureOr<BlockView> fetch(
      ChunkSource chunks,
      MinecraftWorld world,
      Cell from,
      Collection<Cell> cells,
      Cell destination) {
    BlockView view = BlockView.around(from, VIEW_XZ_RADIUS, VIEW_DY_LOW, VIEW_DY_HIGH);
    return fill(chunks, world, view, cells, destination);
  }

  /** Fetches cells outside the view box into a view of their own. */
  static FutureOr<BlockView> fetchApart(
      ChunkSource chunks, MinecraftWorld world, Collection<Cell> cells, Cell destination) {
    if (cells.isEmpty()) {
      return FutureOr.of(BlockView.empty());
    }
    return fill(chunks, world, BlockView.bounding(cells), cells, destination);
  }

  /**
   * Fetches every block of a box around {@code from} into a view of its own, chunk by chunk. For a
   * read of the whole neighborhood rather than a few cells: no cell is listed, so a box of hundreds
   * of blocks allocates nothing per block. Cells outside the world stay unknown.
   */
  static FutureOr<BlockView> fetchBox(
      ChunkSource chunks,
      MinecraftWorld world,
      Cell from,
      int xzRadius,
      int dyLow,
      int dyHigh,
      Cell destination) {
    BlockView view = BlockView.around(from, xzRadius, dyLow, dyHigh);
    int minX = from.x() - xzRadius;
    int maxX = from.x() + xzRadius;
    int minZ = from.z() - xzRadius;
    int maxZ = from.z() + xzRadius;
    int minY = Math.max(from.y() + dyLow, world.minY());
    int maxY = Math.min(from.y() + dyHigh, world.maxY());
    if (minY > maxY) {
      return FutureOr.of(view);
    }
    List<CompletableFuture<Void>> pending = null;
    for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
      for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
        int x0 = Math.max(minX, chunkX << 4);
        int x1 = Math.min(maxX, (chunkX << 4) + 15);
        int z0 = Math.max(minZ, chunkZ << 4);
        int z1 = Math.min(maxZ, (chunkZ << 4) + 15);
        FutureOr<MinecraftChunk> chunk = chunks.chunk(new Cell(x0, minY, z0), world, destination);
        if (chunk.isImmediate()) {
          readBox(view, chunk.value(), x0, x1, minY, maxY, z0, z1);
        } else {
          if (pending == null) {
            pending = new ArrayList<>(4);
          }
          pending.add(chunk.future().thenAccept(c -> readBox(view, c, x0, x1, minY, maxY, z0, z1)));
        }
      }
    }
    if (pending == null) {
      return FutureOr.of(view);
    }
    // Each chunk writes its own disjoint slots; see fill.
    return FutureOr.ofFuture(
        CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
            .thenApply(ignored -> view));
  }

  private static void readBox(
      BlockView view, MinecraftChunk chunk, int x0, int x1, int y0, int y1, int z0, int z1) {
    for (int x = x0; x <= x1; x++) {
      for (int z = z0; z <= z1; z++) {
        for (int y = y0; y <= y1; y++) {
          view.put(x, y, z, chunk.block(x & 15, y, z & 15));
        }
      }
    }
  }

  /** Resolves each distinct chunk the cells fall in and writes their blocks into the view. */
  private static FutureOr<BlockView> fill(
      ChunkSource chunks,
      MinecraftWorld world,
      BlockView view,
      Collection<Cell> cells,
      Cell destination) {
    if (cells.isEmpty()) {
      return FutureOr.of(view);
    }
    Map<Long, List<Cell>> byChunk = new HashMap<>(4);
    for (Cell cell : cells) {
      byChunk.computeIfAbsent(chunkKey(cell), key -> new ArrayList<>()).add(cell);
    }

    List<CompletableFuture<Void>> pending = null;
    for (List<Cell> inChunk : byChunk.values()) {
      FutureOr<MinecraftChunk> chunk = chunks.chunk(inChunk.get(0), world, destination);
      if (chunk.isImmediate()) {
        readInto(view, chunk.value(), inChunk, world);
      } else {
        if (pending == null) {
          pending = new ArrayList<>(byChunk.size());
        }
        pending.add(chunk.future().thenAccept(s -> readInto(view, s, inChunk, world)));
      }
    }
    if (pending == null) {
      return FutureOr.of(view);
    }
    // Each chunk writes its own disjoint slots, and allOf orders every one of those writes before
    // the view is handed on — so the array needs no synchronization of its own.
    return FutureOr.ofFuture(
        CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
            .thenApply(ignored -> view));
  }

  /** Reads every cell of one chunk out of its snapshot; out-of-world cells resolve to unknown. */
  private static void readInto(
      BlockView view, MinecraftChunk chunk, List<Cell> cells, MinecraftWorld world) {
    for (Cell cell : cells) {
      view.put(
          cell,
          cell.y() < world.minY() || cell.y() > world.maxY()
              ? UnknownBlock.INSTANCE
              : chunk.block(cell.x() & 15, cell.y(), cell.z() & 15));
    }
  }

  /** Packs a cell's chunk coordinates into one long, so grouping allocates no key. */
  private static long chunkKey(Cell cell) {
    return ((long) (cell.x() >> 4) << 32) ^ ((cell.z() >> 4) & 0xffffffffL);
  }
}
