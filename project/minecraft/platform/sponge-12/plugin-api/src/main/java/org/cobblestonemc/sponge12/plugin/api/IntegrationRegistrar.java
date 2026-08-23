/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin.api;

import org.spongepowered.plugin.PluginContainer;

/**
 * Where a plugin registers the things Cobblestone's {@code /navigate} command surfaces: destination
 * providers (targets) and navigators (display strategies). Cobblestone provides one registrar
 * (obtained via {@link CobblestonePluginApi#registrar()}) and owns the resulting collection.
 *
 * <p>Everything an owner registers is dropped automatically when that plugin stops.
 */
public interface IntegrationRegistrar {

  /**
   * Registers a destination provider — its trees appear under {@code /navigate}.
   *
   * @param owner the registering plugin
   * @param provider the destination provider (queried per-player when a search is run)
   */
  void registerDestinations(PluginContainer owner, DestinationService provider);

  /**
   * Registers a navigator (a display strategy for guided trips) under an id, chosen with {@code
   * /navigate -navigator <id>}.
   *
   * @param owner the registering plugin
   * @param id the navigator id
   * @param factory the factory that builds the navigator for a trip
   */
  void registerNavigator(PluginContainer owner, String id, NavigatorFactory factory);
}
