/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin;

import java.util.UUID;
import org.cobblestonemc.plugin.hud.TripHud;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * The platform-neutral teardown for a departing player, so no work runs for someone who is gone.
 * Each platform's logout listener delegates here.
 */
public final class LogoutCleanup {

  private LogoutCleanup() {}

  /**
   * Tears down everything held for {@code player}.
   *
   * @param player the departing player's id
   * @param trips the trip manager
   * @param searches the search registry
   * @param hud the trip HUD
   */
  public static void onLogout(
      UUID player, TripManager<?, ?, ?> trips, SearchRegistry<?> searches, TripHud<?, ?, ?> hud) {
    trips.stopAll(player);
    searches.cancelAll(player);
    hud.forget(player);
  }
}
