/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

/**
 * Creates a {@link Navigator} for a player + path, entirely in native terms. Registered by
 * (lower-cased) id via
 * {@link PlatformOdysseyPluginApi#registerNavigatorFactory(String, NavigatorFactory)}; Odyssey ships
 * the default {@code trail} factory.
 *
 * @param <P> the native player type (e.g. {@code org.bukkit.entity.Player})
 * @param <L> the native location type (e.g. {@code org.bukkit.Location})
 */
@FunctionalInterface
public interface NavigatorFactory<P, L> {

  /**
   * Creates a navigator.
   *
   * @param player the player to guide
   * @param path the path to follow
   * @param context the navigator's runtime services
   * @return the navigator
   */
  Navigator<L> create(P player, MinecraftPath<L> path, NavigatorContext<P> context);
}
