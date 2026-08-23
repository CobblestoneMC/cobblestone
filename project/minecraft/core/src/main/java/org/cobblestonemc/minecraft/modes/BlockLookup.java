/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.UnknownBlock;

/**
 * Fetches the set of blocks a mode needs for one expansion and packages them as a {@link
 * BlockView}.
 *
 * <p>The result is {@link FutureOr#isImmediate() immediate} when every block was a cache hit — the
 * common case — and pending only when at least one block is being loaded, in which case the search
 * parks once until all of them arrive (matching the "park once per expansion" model).
 */
final class BlockLookup {

  private BlockLookup() {}

  static FutureOr<BlockView> fetch(MinecraftWorld world, Collection<Cell> cells, Cell destination) {
    Map<Cell, MinecraftBlock> immediate = new HashMap<>();
    Map<Cell, CompletableFuture<MinecraftBlock>> pending = new HashMap<>();
    for (Cell cell : cells) {
      FutureOr<MinecraftBlock> block = world.blockAt(cell, destination);
      if (block.isImmediate()) {
        immediate.put(cell, block.value());
      } else {
        pending.put(cell, block.future());
      }
    }
    if (pending.isEmpty()) {
      return FutureOr.of(new BlockView(immediate));
    }
    CompletableFuture<BlockView> combined =
        CompletableFuture.allOf(pending.values().toArray(new CompletableFuture<?>[0]))
            .thenApply(
                ignored -> {
                  Map<Cell, MinecraftBlock> merged = new HashMap<>(immediate);
                  pending.forEach(
                      (cell, future) -> merged.put(cell, future.getNow(UnknownBlock.INSTANCE)));
                  return new BlockView(merged);
                });
    return FutureOr.ofFuture(combined);
  }
}
