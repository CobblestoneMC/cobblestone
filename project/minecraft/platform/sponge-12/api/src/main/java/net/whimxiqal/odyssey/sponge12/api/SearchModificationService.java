/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;

/**
 * The single hook by which a plugin influences Odyssey's searches. Register one with Odyssey's
 * {@link SearchModificationRegistrar} and Odyssey folds it into every search.
 *
 * <p>A modifier can do three independent things, each optional (override only what you need):
 *
 * <ul>
 *   <li>{@link #computeTransitions} — teach Odyssey travel routes pathfinding can't discover
 *       (command warps, teleports, minecart lines) as {@link Transition}s.
 *   <li>{@link #computeBreakChecker} — forbid the mining mode from breaking certain blocks.
 *   <li>{@link #computePassChecker} — bar the player from entering certain cells entirely.
 * </ul>
 *
 * <p><b>Threading.</b> The {@code compute*} methods are called once at the start of each search, on
 * the thread that initiates it (normally the server thread), so reading Sponge state there is safe.
 * The checker objects they return are invoked repeatedly <i>during</i> the search, possibly off the
 * main thread; they answer with a {@link CompletableFuture} so an async lookup never blocks.
 */
public interface SearchModificationService {

  /**
   * Computes the extra transitions available to the given player for one search.
   *
   * @param player the player about to be routed
   * @return the transitions, in a future (use {@link CompletableFuture#completedFuture} when
   *     synchronous)
   */
  default CompletableFuture<List<Transition>> computeTransitions(ServerPlayer player) {
    return CompletableFuture.completedFuture(List.of());
  }

  /**
   * Computes the mining-breakability check for the given player's search. Defaults to permitting
   * all breaking.
   *
   * @param player the player about to be routed
   * @return the break checker to apply for this search
   */
  default BreakChecker computeBreakChecker(ServerPlayer player) {
    return BreakChecker.ALLOW;
  }

  /**
   * Computes the passability check for the given player's search. Defaults to permitting entry
   * everywhere.
   *
   * @param player the player about to be routed
   * @return the passability checker to apply for this search
   */
  default PassChecker computePassChecker(ServerPlayer player) {
    return PassChecker.ALLOW;
  }
}
