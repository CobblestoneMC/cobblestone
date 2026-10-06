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
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.sidebar.TripSidebar;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * Cleans up a player's navigation state on logout: stop their trips, cancel their in-flight
 * searches, and close their sidebar so no work runs for an absent player. The teardown itself lives
 * in the platform-neutral {@link LogoutCleanup}; this class is just the Bukkit event binding.
 */
final class CobblestoneListener implements Listener {

  private final TripManager<Entity, PaperTripAgent, Location> trips;
  private final SearchRegistry<Location> searches;
  private final TripSidebar<Entity, PaperTripAgent, Location> sidebar;

  CobblestoneListener(
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches,
      TripSidebar<Entity, PaperTripAgent, Location> sidebar) {
    this.trips = trips;
    this.searches = searches;
    this.sidebar = sidebar;
  }

  @EventHandler
  void onQuit(PlayerQuitEvent event) {
    LogoutCleanup.onLogout(event.getPlayer().getUniqueId(), trips, searches, sidebar);
  }
}
