/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.DeathLocationDao;

/**
 * Remembers where each player last died, so they can navigate back to it ({@code /navigate death}).
 * Only the most recent death is kept — each one overwrites the last.
 *
 * <p>Read at MONITOR, after any plugin that might cancel or relocate the death, and persisted
 * off-thread like the rest of Cobblestone's writes.
 */
final class DeathListener implements Listener {

  private final DeathLocationDao deaths;
  private final MinecraftScheduler<?> scheduler;
  private final BooleanSupplier enabled;

  DeathListener(DeathLocationDao deaths, MinecraftScheduler<?> scheduler, BooleanSupplier enabled) {
    this.deaths = deaths;
    this.scheduler = scheduler;
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
    scheduler.runAsync(() -> deaths.upsert(death));
  }
}
