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
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.UnknownBlock;

/**
 * Fetches the blocks one expansion reads and packages them as a {@link BlockView}.
 *
 * <p><b>Chunks, not blocks.</b> An expansion's neighborhood spans only a handful of chunks —
 * usually one — so cells are grouped by chunk and each distinct chunk is resolved once, then
 * indexed directly. This is the innermost loop of a search, so it matters.
 */
final class BlockLookup {

  /**
   * The box every local movement rule reads within, around the expanding cell. Widen it if a rule
   * ever reaches further; {@code BlockLookupTest} fails if one does.
   */
  static final int VIEW_XZ_RADIUS = 2;

  static final int VIEW_DY_LOW = -2;
  static final int VIEW_DY_HIGH = 3;

  private BlockLookup() {}

  /** Fetches the given cells around {@code from}. Every cell must lie within the view box. */
  static FutureOr<BlockView> fetch(
      MinecraftWorld world, Cell from, Collection<Cell> cells, Cell destination) {
    BlockView view = BlockView.around(from, VIEW_XZ_RADIUS, VIEW_DY_LOW, VIEW_DY_HIGH);
    return fill(world, view, cells, destination);
  }

  /**
   * Fetches cells outside the view box — a second-phase scan, such as the columns under a ledge —
   * into a view of their own.
   */
  static FutureOr<BlockView> fetchApart(
      MinecraftWorld world, Collection<Cell> cells, Cell destination) {
    if (cells.isEmpty()) {
      return FutureOr.of(BlockView.empty());
    }
    return fill(world, BlockView.bounding(cells), cells, destination);
  }

  /** Resolves each distinct chunk the cells fall in and writes their blocks into the view. */
  private static FutureOr<BlockView> fill(
      MinecraftWorld world, BlockView view, Collection<Cell> cells, Cell destination) {
    if (cells.isEmpty()) {
      return FutureOr.of(view);
    }
    Map<Long, List<Cell>> byChunk = new HashMap<>(4);
    for (Cell cell : cells) {
      byChunk.computeIfAbsent(chunkKey(cell), key -> new ArrayList<>()).add(cell);
    }

    List<CompletableFuture<Void>> pending = null;
    for (List<Cell> inChunk : byChunk.values()) {
      FutureOr<MinecraftChunk> chunk = world.chunkAt(inChunk.get(0), destination);
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
