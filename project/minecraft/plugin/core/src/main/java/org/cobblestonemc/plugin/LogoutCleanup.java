/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin;

import java.util.UUID;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.sidebar.TripSidebar;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * The platform-neutral teardown for a departing player: stop their trips, cancel their in-flight
 * searches, and close their sidebar so no work runs for someone who is gone. Each platform's logout
 * listener (Bukkit {@code PlayerQuitEvent}, Sponge {@code ServerSideConnectionEvent.Disconnect})
 * delegates here, so the two stay in step as cleanup grows.
 */
public final class LogoutCleanup {

  private LogoutCleanup() {}

  /**
   * Stops all of {@code player}'s trips, cancels all their searches, and closes their sidebar.
   *
   * @param player the departing player's id
   * @param trips the trip manager
   * @param searches the search registry
   * @param sidebar the trip sidebar
   */
  public static void onLogout(
      UUID player,
      TripManager<?, ?, ?> trips,
      SearchRegistry<?> searches,
      TripSidebar<?, ?, ?> sidebar) {
    trips.stopAll(player);
    searches.cancelAll(player);
    sidebar.forget(player);
  }
}
