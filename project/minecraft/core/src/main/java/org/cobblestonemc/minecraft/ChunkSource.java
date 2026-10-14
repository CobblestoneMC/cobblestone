/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;

/**
 * Where a search reads blocks from. A {@link MinecraftWorld} only names a place; what is in it is
 * read through one of these, which is the search's own and goes away with it. Production code uses
 * a {@link ChunkProvider}, created per search.
 *
 * <p>Both reads are {@link FutureOr}s: immediate when the chunk is already in hand, pending while
 * it is fetched. A chunk that cannot be had under the configured policy reads as {@link
 * MinecraftChunk.Unknown}, every block of it impassable.
 */
@FunctionalInterface
public interface ChunkSource {

  /**
   * Returns the snapshot of the chunk containing {@code cell}, possibly pending on a fetch.
   *
   * <p>A caller wanting many blocks around one point resolves the handful of chunks they fall in
   * and indexes the snapshots directly, rather than paying a per-block lookup a few hundred times
   * per expansion. Local coordinates are {@code x & 15} and {@code z & 15}; a {@code y} outside the
   * world's {@link MinecraftWorld#minY() minY}..{@link MinecraftWorld#maxY() maxY} has no block and
   * the caller must treat it as unknown.
   *
   * @param cell a cell in the wanted chunk
   * @param world the world
   * @param destination the destination of whatever this process is moving in, for read-ahead
   * @return the chunk snapshot, immediate or pending
   */
  FutureOr<MinecraftChunk> chunk(Cell cell, MinecraftWorld world, Cell destination);

  /**
   * Returns the block at {@code cell}, possibly pending on a chunk fetch. A cell outside the
   * world's vertical bounds is an impassable unknown block.
   *
   * @param cell the cell
   * @param world the world
   * @param destination the destination of whatever this process is moving in, for read-ahead
   * @return the block, immediate or pending
   */
  default FutureOr<MinecraftBlock> block(Cell cell, MinecraftWorld world, Cell destination) {
    if (cell.y() < world.minY() || cell.y() > world.maxY()) {
      return FutureOr.of(UnknownBlock.INSTANCE);
    }
    return chunk(cell, world, destination)
        .map(snapshot -> snapshot.block(cell.x() & 15, cell.y(), cell.z() & 15));
  }
}
