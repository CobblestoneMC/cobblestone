/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.function.BooleanSupplier;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.DeathLocationDao;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.entity.DestructEntityEvent;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * Remembers where each player last died, so they can navigate back to it ({@code /navigate death}).
 * Only the most recent death is kept — each one overwrites the last.
 *
 * <p>Read last, after any plugin that might change the outcome of the death, and persisted
 * off-thread like the rest of Cobblestone's writes.
 */
final class SpongeDeathListener {

  private final DeathLocationDao deaths;
  private final MinecraftScheduler<?> scheduler;
  private final BooleanSupplier enabled;

  SpongeDeathListener(
      DeathLocationDao deaths, MinecraftScheduler<?> scheduler, BooleanSupplier enabled) {
    this.deaths = deaths;
    this.scheduler = scheduler;
    this.enabled = enabled;
  }

  /** Records a player's death location. */
  @Listener(order = Order.POST)
  public void onDeath(DestructEntityEvent.Death event) {
    if (!enabled.getAsBoolean() || !(event.entity() instanceof ServerPlayer player)) {
      return;
    }
    ServerLocation location = player.serverLocation();
    DeathLocation death =
        new DeathLocation(
            player.uniqueId(),
            location.worldKey().asString(),
            location.blockX(),
            location.blockY(),
            location.blockZ());
    scheduler.runAsync(() -> deaths.upsert(death));
  }
}
