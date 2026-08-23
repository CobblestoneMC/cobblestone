/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.api;

import java.util.concurrent.CompletableFuture;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * Decides whether a player may enter a given cell at all. Returned from {@link
 * SearchModificationService#computePassChecker} and invoked for each cell the search proposes; a
 * cell the player may not enter is dropped from the route. When several modifiers are registered, a
 * cell is passable only if all of them permit it.
 *
 * <p>Answer with a {@link CompletableFuture} so a region/permission lookup may be asynchronous;
 * return {@link CompletableFuture#completedFuture} for a synchronous decision, which keeps the
 * search on its fast path.
 */
@FunctionalInterface
public interface PassChecker {

  /** A checker that permits entering every cell. */
  PassChecker ALLOW = (player, location) -> CompletableFuture.completedFuture(true);

  /**
   * Returns whether the player may enter the block at the given location.
   *
   * @param player the navigating player
   * @param location the location the player would enter
   * @return a future of {@code true} if entry is permitted
   */
  CompletableFuture<Boolean> passable(ServerPlayer player, ServerLocation location);
}
