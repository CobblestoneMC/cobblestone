/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A lazy, per-player, asynchronous supplier of {@link PlatformSingleCellTransition}s in native
 * platform terms. Developers register these (via the platform API) to expose custom wormholes.
 *
 * @param <P> the native player type (e.g. {@code org.bukkit.entity.Player})
 * @param <L> the native location type (e.g. {@code org.bukkit.Location})
 */
@FunctionalInterface
public interface PlatformSingleCellTransitionProvider<P, L> {

  /**
   * Computes the transitions available to the given player.
   *
   * @param player the player
   * @return a future of the available transitions
   */
  CompletableFuture<List<? extends PlatformSingleCellTransition<L>>> compute(P player);
}
