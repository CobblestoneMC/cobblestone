/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;

/** Decides whether a block may be broken while navigating. */
@FunctionalInterface
public interface BreakChecker {

  /**
   * Returns whether the given block may be broken.
   *
   * @param cell the block's cell
   * @param world the world the block is in
   * @param block the block
   * @return a future of {@code true} if breaking is permitted
   */
  CompletableFuture<Boolean> breakable(Cell cell, MinecraftWorld world, MinecraftBlock block);
}
