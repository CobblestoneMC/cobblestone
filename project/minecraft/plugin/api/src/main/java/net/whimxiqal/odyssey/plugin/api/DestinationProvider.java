/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

/**
 * The root of a destination tree, evaluated per player (in native terms) so results can depend on
 * who is asking — their permissions, homes, town membership, and so on.
 *
 * <p>Integration plugins (Essentials, Towny, quest plugins) register providers via
 * {@link PlatformOdysseyPluginApi#registerDestinationProvider(DestinationProvider)}; Odyssey itself
 * registers one for waypoints.
 *
 * @param <P> the native player type (e.g. {@code org.bukkit.entity.Player})
 */
@FunctionalInterface
public interface DestinationProvider<P> {

  /**
   * Builds the destination tree visible to the given player.
   *
   * @param player the player requesting navigation
   * @return the (lazily-evaluated) tree
   */
  DestinationTree provide(P player);
}
