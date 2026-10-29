/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.DeathLocationDao;

/**
 * Remembers where each player last died, so they can navigate back to it ({@code /navigate death}).
 * Only the most recent death is kept — each one overwrites the last.
 *
 * <p>Read at MONITOR, after any plugin that might cancel or relocate the death, and persisted
 * off-thread like the rest of Cobblestone's writes.
 */
final class PaperDeathListener implements Listener {

  private final DeathLocationDao deaths;
  private final Executor data;
  private final BooleanSupplier enabled;

  PaperDeathListener(DeathLocationDao deaths, Executor data, BooleanSupplier enabled) {
    this.deaths = deaths;
    this.data = data;
    this.enabled = enabled;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(PlayerDeathEvent event) {
    if (!enabled.getAsBoolean()) {
      return;
    }
    Location location = event.getEntity().getLocation();
    if (location.getWorld() == null) {
      return;
    }
    DeathLocation death =
        new DeathLocation(
            event.getEntity().getUniqueId(),
            location.getWorld().getKey().asString(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ());
    data.execute(() -> deaths.upsert(death));
  }
}
