/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.cobblestonemc.plugin.LogoutCleanup;
import org.cobblestonemc.plugin.hud.TripHud;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.TripManager;

/** Binds the Bukkit quit event to {@link LogoutCleanup}. */
final class CobblestoneListener implements Listener {

  private final TripManager<Entity, PaperTripAgent, Location> trips;
  private final SearchRegistry<Location> searches;
  private final TripHud<Entity, PaperTripAgent, Location> hud;

  CobblestoneListener(
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches,
      TripHud<Entity, PaperTripAgent, Location> hud) {
    this.trips = trips;
    this.searches = searches;
    this.hud = hud;
  }

  @EventHandler
  void onQuit(PlayerQuitEvent event) {
    LogoutCleanup.onLogout(event.getPlayer().getUniqueId(), trips, searches, hud);
  }
}
