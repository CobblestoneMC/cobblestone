/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import java.util.Objects;
import net.whimxiqal.odyssey.paper.api.PaperNavigationService;
import net.whimxiqal.odyssey.paper.api.PaperSearchModificationService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * One-line registration of an integration's hooks into Odyssey. Each {@code register} is exactly
 * the Bukkit-service call Odyssey discovers, without the class token, priority, or boilerplate:
 *
 * <pre>{@code
 * public void onEnable() {
 *   Odyssey.register(this, new MyDestinationProvider());
 *   Odyssey.register(this, new MySearchModifier());
 * }
 * }</pre>
 *
 * <p>There is nothing to undo on disable — Bukkit unregisters a plugin's services automatically
 * when it is disabled.
 */
public final class Odyssey {

  private Odyssey() {}

  /**
   * The navigation service — run searches and compute paths.
   *
   * @return the registered navigation service
   */
  public static PaperNavigationService navigationService() {
    return Objects.requireNonNull(
            Bukkit.getServicesManager().getRegistration(PaperNavigationService.class))
        .getProvider();
  }

  /**
   * The trip service — start a guided trip for a player (search-then-guide, or from a path you
   * already computed). This is what quest/integration plugins use to "take me there".
   *
   * @return the registered trip service
   */
  public static PaperTripService tripService() {
    return Objects.requireNonNull(
            Bukkit.getServicesManager().getRegistration(PaperTripService.class))
        .getProvider();
  }

  /**
   * Registers a search modifier (transitions, breakability, passability).
   *
   * @param plugin the registering plugin
   * @param service the service to register
   */
  public static void register(Plugin plugin, PaperSearchModificationService service) {
    register(plugin, PaperSearchModificationService.class, service);
  }

  /**
   * Registers a destination provider (targets shown in {@code /navigate}).
   *
   * @param plugin the registering plugin
   * @param service the service to register
   */
  public static void register(Plugin plugin, PaperDestinationService service) {
    register(plugin, PaperDestinationService.class, service);
  }

  /**
   * Registers a navigator factory (a display strategy for guided trips).
   *
   * @param plugin the registering plugin
   * @param service the service to register
   */
  public static void register(Plugin plugin, PaperNavigatorService service) {
    register(plugin, PaperNavigatorService.class, service);
  }

  private static <T> void register(Plugin plugin, Class<T> type, T service) {
    Bukkit.getServicesManager().register(type, service, plugin, ServicePriority.Normal);
  }
}
