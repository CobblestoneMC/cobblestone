/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.api;

import java.util.Objects;
import org.bukkit.Bukkit;

/**
 * The core-layer entry point for developers building on Cobblestone's navigation library: get the
 * raw {@link NavigationService} (compute a path, no guided trip) and register search modifiers.
 * Both are backed by an Cobblestone-provided Bukkit service, so an alternative Cobblestone
 * implementation could supply its own.
 *
 * <p>The plugin-layer entry point ({@code CobblestonePlugin}, in the plugin API) offers the trip
 * service and destination/navigator registration.
 */
public final class CobblestoneCoreApi {

  private CobblestoneCoreApi() {}

  /**
   * The navigation service — run searches and compute paths.
   *
   * @return the registered navigation service
   */
  public static NavigationService navigationService() {
    return Objects.requireNonNull(
            Bukkit.getServicesManager().getRegistration(NavigationService.class),
            "Cobblestone navigation service is not registered")
        .getProvider();
  }

  /**
   * The registrar for search modifiers (transitions, break-checks, pass-checks).
   *
   * @return the registered search-modification registrar
   */
  public static SearchModificationRegistrar registrar() {
    return Objects.requireNonNull(
            Bukkit.getServicesManager().getRegistration(SearchModificationRegistrar.class),
            "Cobblestone search-modification registrar is not registered")
        .getProvider();
  }
}
