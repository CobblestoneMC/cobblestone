/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import net.whimxiqal.odyssey.paper.api.PaperNavigationService;
import net.whimxiqal.odyssey.paper.api.PaperOdysseySearchModifier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;

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

  public static PaperNavigationService navigationService() {
    return Objects.requireNonNull(Bukkit.getServicesManager().getRegistration(PaperNavigationService.class)).getProvider();
  }

  /**
   * Registers a search modifier (transitions, breakability, passability).
   *
   * @param plugin the registering plugin
   * @param modifier the modifier to register
   */
  public static void register(Plugin plugin, PaperOdysseySearchModifier modifier) {
    register(plugin, PaperOdysseySearchModifier.class, modifier);
  }

  /**
   * Registers a destination provider (targets shown in {@code /navigate}).
   *
   * @param plugin the registering plugin
   * @param provider the provider to register
   */
  public static void register(Plugin plugin, PaperDestinationProvider provider) {
    register(plugin, PaperDestinationProvider.class, provider);
  }

  /**
   * Registers a navigator factory (a display strategy for guided trips).
   *
   * @param plugin the registering plugin
   * @param factory the factory to register
   */
  public static void register(Plugin plugin, PaperNavigatorFactory factory) {
    register(plugin, PaperNavigatorFactory.class, factory);
  }

  private static <T> void register(Plugin plugin, Class<T> type, T service) {
    Bukkit.getServicesManager().register(type, service, plugin, ServicePriority.Normal);
  }
}
