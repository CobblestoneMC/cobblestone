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
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * Cleans up a player's navigation state on logout: stop their trips and cancel their in-flight
 * searches so no work runs for an absent player. The teardown itself lives in the platform-neutral
 * {@link LogoutCleanup}; this class is just the Bukkit event binding.
 */
final class CobblestoneListener implements Listener {

  private final TripManager<Entity, PaperTripAgent, Location> trips;
  private final SearchRegistry<Location> searches;

  CobblestoneListener(
      TripManager<Entity, PaperTripAgent, Location> trips, SearchRegistry<Location> searches) {
    this.trips = trips;
    this.searches = searches;
  }

  @EventHandler
  void onQuit(PlayerQuitEvent event) {
    LogoutCleanup.onLogout(event.getPlayer().getUniqueId(), trips, searches);
  }
}
