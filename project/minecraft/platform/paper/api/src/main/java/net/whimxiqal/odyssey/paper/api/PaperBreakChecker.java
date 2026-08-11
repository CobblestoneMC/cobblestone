/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Decides whether Odyssey may route a player through mining a given block. Returned from {@link
 * PaperOdysseySearchModifier#computeBreakChecker} and invoked for each block the mining mode
 * considers breaking. When several modifiers are registered, a block is breakable only if all of
 * them permit it.
 *
 * <p>The block is supplied as a chunk-snapshot {@link BlockData} (its {@link org.bukkit.Material}
 * and state), not a live world block — the search runs ahead of chunk loading. Answer with a {@link
 * CompletableFuture} so a permission lookup may be asynchronous; return {@link
 * CompletableFuture#completedFuture} for a synchronous decision (e.g. a block-type rule), which
 * keeps the search on its fast path.
 */
@FunctionalInterface
public interface PaperBreakChecker {

  /** A checker that permits breaking every block. */
  PaperBreakChecker ALLOW = (player, location, block) -> CompletableFuture.completedFuture(true);

  /**
   * Returns whether the player may break the block at the given location.
   *
   * @param player the navigating player
   * @param location the block's location
   * @param block the block's data (type and state) from the chunk snapshot
   * @return a future of {@code true} if breaking is permitted
   */
  CompletableFuture<Boolean> breakable(Player player, Location location, BlockData block);
}
