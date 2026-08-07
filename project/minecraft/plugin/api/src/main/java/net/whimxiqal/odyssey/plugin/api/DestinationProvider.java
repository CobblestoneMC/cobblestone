/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

@FunctionalInterface
public interface DestinationProvider<W, V, P> {

  /**
   * Builds the destination tree visible to the given player.
   *
   * @param player the player requesting navigation
   * @return the (lazily-evaluated) tree
   */
  DestinationTree<W, V> provide(P player);
}
