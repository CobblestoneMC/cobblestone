/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin.api;

import java.util.Objects;

/**
 * The plugin-layer entry point for integrations: register destinations/navigators and start guided
 * trips through the installed Odyssey plugin.
 *
 * <p>Sponge has no service manager, so the Odyssey plugin publishes these through a static accessor
 * (installed when Odyssey starts, cleared when it stops). Search modifiers and the raw navigation
 * service live one layer down, on {@code OdysseyCoreAPI} (in the core API).
 */
public final class OdysseyPluginAPI {

  private static volatile IntegrationRegistrar registrar;
  private static volatile TripService tripService;

  private OdysseyPluginAPI() {}

  /**
   * Publishes the plugin-layer services. Called by the Odyssey plugin as it starts; not for general
   * use.
   *
   * @param integrationRegistrar the destination/navigator registrar
   * @param service the trip service
   */
  public static void install(IntegrationRegistrar integrationRegistrar, TripService service) {
    registrar = integrationRegistrar;
    tripService = service;
  }

  /** Withdraws the plugin-layer services. Called by the Odyssey plugin as it stops. */
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
    return Objects.requireNonNull(registrar, "Odyssey integration registrar is not installed");
  }

  /**
   * The trip service — start a guided trip for a player (search-then-guide, or from a path you
   * already computed).
   *
   * @return the installed trip service
   */
  public static TripService tripService() {
    return Objects.requireNonNull(tripService, "Odyssey trip service is not installed");
  }
}
