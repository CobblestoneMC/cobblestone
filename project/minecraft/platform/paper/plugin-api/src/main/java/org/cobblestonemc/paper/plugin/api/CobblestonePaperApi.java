/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import java.util.Objects;
import org.bukkit.Bukkit;

/**
 * The plugin-layer entry point for integrations: register destinations/navigators and start guided
 * trips through the installed Cobblestone plugin.
 *
 * <pre>{@code
 * public void onEnable() {
 *   CobblestonePlugin.registrar().registerDestinations(this, new MyDestinationService());
 *   CobblestonePlugin.tripService().navigate(player, location, settings, "my label");
 * }
 * }</pre>
 *
 * <p>Search modifiers and the raw navigation service live one layer down, on {@code
 * CobblestoneCore} (in the core API). Both entry points are backed by Cobblestone-provided Bukkit
 * services.
 */
public final class CobblestonePaperApi {

  private CobblestonePaperApi() {}

  /**
   * The registrar for destinations and navigators.
   *
   * @return the registered integration registrar
   */
  public static IntegrationRegistrar registrar() {
    return Objects.requireNonNull(
            Bukkit.getServicesManager().getRegistration(IntegrationRegistrar.class),
            "Cobblestone integration registrar is not registered")
        .getProvider();
  }

  /**
   * The trip service — start a guided trip for a player (search-then-guide, or from a path you
   * already computed). This is what quest/integration plugins use to "take me there".
   *
   * @return the registered trip service
   */
  public static TripService tripService() {
    return Objects.requireNonNull(
            Bukkit.getServicesManager().getRegistration(TripService.class),
            "Cobblestone trip service is not registered")
        .getProvider();
  }
}
