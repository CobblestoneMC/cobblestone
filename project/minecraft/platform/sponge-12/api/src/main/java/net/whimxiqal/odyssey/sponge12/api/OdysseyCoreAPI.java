/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.api;

import java.util.Objects;

/**
 * The core-layer entry point for developers building on Odyssey's navigation library: get the raw
 * {@link NavigationService} (compute a path, no guided trip) and register search modifiers.
 *
 * <p>Sponge has no service manager, so the Odyssey plugin publishes these through a static accessor
 * (installed when Odyssey starts, cleared when it stops) rather than a registered service. Callers
 * must not cache the returned objects across an Odyssey reload.
 *
 * <p>The plugin-layer entry point ({@code OdysseyPlugin}, in the plugin API) offers the trip
 * service and destination/navigator registration.
 */
public final class OdysseyCoreAPI {

  private static volatile NavigationService navigationService;
  private static volatile SearchModificationRegistrar registrar;

  private OdysseyCoreAPI() {}

  /**
   * Publishes the core services. Called by the Odyssey plugin as it starts; not for general use.
   *
   * @param service the navigation service
   * @param modificationRegistrar the search-modification registrar
   */
  public static void install(
      NavigationService service, SearchModificationRegistrar modificationRegistrar) {
    navigationService = service;
    registrar = modificationRegistrar;
  }

  /** Withdraws the core services. Called by the Odyssey plugin as it stops. */
  public static void uninstall() {
    navigationService = null;
    registrar = null;
  }

  /**
   * The navigation service — run searches and compute paths.
   *
   * @return the installed navigation service
   */
  public static NavigationService navigationService() {
    return Objects.requireNonNull(navigationService, "Odyssey navigation service is not installed");
  }

  /**
   * The registrar for search modifiers (transitions, break-checks, pass-checks).
   *
   * @return the installed search-modification registrar
   */
  public static SearchModificationRegistrar registrar() {
    return Objects.requireNonNull(
        registrar, "Odyssey search-modification registrar is not installed");
  }
}
