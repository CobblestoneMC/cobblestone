/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin;

import java.util.UUID;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.TripManager;

/**
 * The platform-neutral teardown for a departing player: stop their trips and cancel their in-flight
 * searches so no work runs for someone who is gone. Each platform's logout listener (Bukkit {@code
 * PlayerQuitEvent}, Sponge {@code ServerSideConnectionEvent.Disconnect}) delegates here, so the two
 * stay in step as cleanup grows.
 */
public final class LogoutCleanup {

  private LogoutCleanup() {}

  /**
   * Stops all of {@code player}'s trips and cancels all their searches.
   *
   * @param player the departing player's id
   * @param trips the trip manager
   * @param searches the search registry
   */
  public static void onLogout(UUID player, TripManager<?, ?, ?> trips, SearchRegistry<?> searches) {
    trips.stopAll(player);
    searches.cancelAll(player);
  }
}
