/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

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
 * Fetches the blocks a mode needs for one expansion and packages them as a {@link BlockView}.
 *
 * <p>Two things keep this cheap, because it is the innermost loop of a search.
 *
 * <p><b>Chunks, not blocks.</b> A mode's neighborhood spans only a handful of chunks — usually one
 * — so cells are grouped by chunk and each distinct chunk is resolved once, then indexed directly.
 *
 * <p><b>One fill per expansion, shared by every mode.</b> The modes run back to back over the same
 * cell and their boxes overlap almost entirely: walking wants {@code (1,-2,2)}, mining {@code
 * (1,-2,3)}, falling and climbing {@code (1,-1,2)}, and so on — around 215 cells fetched to answer
 * some 70 distinct questions, six times over. So a fill is memoized per expansion (keyed on world
 * and cell, held per thread) and each later mode fetches only the cells nobody has filled yet;
 * usually none.
 *
 * <p>A view is only ever memoized while every write into it stays on the filling thread. A pending
 * chunk completes elsewhere, and the search keeps running the remaining modes synchronously
 * meanwhile — so a shared view still being written would hand those modes a half-filled box, where
 * an unwritten cell reads as unknown and therefore impassable. A fill that goes pending drops the
 * memo instead and keeps its view to itself.
 */
final class BlockLookup {

  /**
   * The box every mode's {@link AbstractMinecraftMode#requiredCells} fits inside, and so the box
   * one shared fill covers. Widen it if a mode ever reaches further; {@code BlockLookupTest} fails
   * if one does.
   */
  static final int SHARED_XZ_RADIUS = 2;

  static final int SHARED_DY_LOW = -2;
  static final int SHARED_DY_HIGH = 3;

  private static final ThreadLocal<Memo> MEMO = new ThreadLocal<>();

  private BlockLookup() {}

  /**
   * Fetches the cells a mode declared around {@code from}, sharing one fill with the other modes
   * expanding the same cell. Every cell must lie within the shared box.
   */
  static FutureOr<BlockView> fetch(
      MinecraftWorld world, Cell from, Collection<Cell> cells, Cell destination) {
    if (cells.isEmpty()) {
      return FutureOr.of(BlockView.empty());
    }
    Memo memo = MEMO.get();
    if (memo != null && memo.matches(world, from)) {
      List<Cell> missing = missingFrom(memo.view, cells);
      if (missing == null) {
        return FutureOr.of(memo.view); // every mode after the first usually lands here
      }
      // Topping up a memoized view: unpublish it first, and publish it again only if the top-up
      // turned out to need no fetch, so no later mode can read a view another thread is writing.
      MEMO.remove();
      return fill(world, memo.view, missing, destination, () -> MEMO.set(memo));
    }
    BlockView view = BlockView.around(from, SHARED_XZ_RADIUS, SHARED_DY_LOW, SHARED_DY_HIGH);
    List<Cell> missing = missingFrom(view, cells);
    return fill(
        world,
        view,
        missing == null ? List.of() : missing,
        destination,
        () -> MEMO.set(new Memo(world, from, view)));
  }

  /**
   * Fetches cells outside any shared box — a second-phase scan, such as the columns under a ledge.
   * Gets its own view and never touches the memo.
   */
  static FutureOr<BlockView> fetchApart(
      MinecraftWorld world, Collection<Cell> cells, Cell destination) {
    if (cells.isEmpty()) {
      return FutureOr.of(BlockView.empty());
    }
    BlockView view = BlockView.bounding(cells);
    return fill(world, view, new ArrayList<>(cells), destination, () -> {});
  }

  /** The cells not yet filled in the view, or {@code null} if they all are. */
  private static List<Cell> missingFrom(BlockView view, Collection<Cell> cells) {
    List<Cell> missing = null;
    for (Cell cell : cells) {
      if (!view.holds(cell)) {
        if (missing == null) {
          missing = new ArrayList<>(cells.size());
        }
        missing.add(cell);
      }
    }
    return missing;
  }

  /**
   * Resolves each distinct chunk the missing cells fall in and writes their blocks into the view.
   */
  private static FutureOr<BlockView> fill(
      MinecraftWorld world,
      BlockView view,
      List<Cell> missing,
      Cell destination,
      Runnable onFullyImmediate) {
    if (missing.isEmpty()) {
      onFullyImmediate.run();
      return FutureOr.of(view);
    }
    Map<Long, List<Cell>> byChunk = new HashMap<>(4);
    for (Cell cell : missing) {
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
      onFullyImmediate.run();
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

  /**
   * The blocks filled for one expansion, reused by every mode expanding that cell.
   *
   * <p>The world is matched by <b>identity</b>, not by {@link MinecraftWorld#key()}. A platform
   * hands out one instance per world and reuses it, so identity is both correct and cheaper than
   * comparing keys — while two distinct worlds sharing a key (as tests build them) must never share
   * a fill.
   */
  private record Memo(MinecraftWorld world, Cell from, BlockView view) {
    boolean matches(MinecraftWorld otherWorld, Cell otherFrom) {
      return world == otherWorld && from.equals(otherFrom);
    }
  }
}
