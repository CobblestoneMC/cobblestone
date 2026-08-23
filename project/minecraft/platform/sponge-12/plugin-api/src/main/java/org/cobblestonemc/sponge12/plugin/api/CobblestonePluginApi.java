/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin.api;

import java.util.Objects;

/**
 * The plugin-layer entry point for integrations: register destinations/navigators and start guided
 * trips through the installed Cobblestone plugin.
 *
 * <p>Sponge has no service manager, so the Cobblestone plugin publishes these through a static
 * accessor (installed when Cobblestone starts, cleared when it stops). Search modifiers and the raw
 * navigation service live one layer down, on {@code CobblestoneCoreAPI} (in the core API).
 */
public final class CobblestonePluginApi {

  private static volatile IntegrationRegistrar registrar;
  private static volatile TripService tripService;

  private CobblestonePluginApi() {}

  /**
   * Publishes the plugin-layer services. Called by the Cobblestone plugin as it starts; not for
   * general use.
   *
   * @param integrationRegistrar the destination/navigator registrar
   * @param service the trip service
   */
  public static void install(IntegrationRegistrar integrationRegistrar, TripService service) {
    registrar = integrationRegistrar;
    tripService = service;
  }

  /** Withdraws the plugin-layer services. Called by the Cobblestone plugin as it stops. */
  public static void uninstall() {
    registrar = null;
    tripService = null;
  }

  /**
   * The registrar for destinations and navigators.
   *
   * @return the installed integration registrar
   */
  public static IntegrationRegistrar registrar() {
    return Objects.requireNonNull(registrar, "Cobblestone integration registrar is not installed");
  }

  /**
   * The trip service — start a guided trip for a player (search-then-guide, or from a path you
   * already computed).
   *
   * @return the installed trip service
   */
  public static TripService tripService() {
    return Objects.requireNonNull(tripService, "Cobblestone trip service is not installed");
  }
}
