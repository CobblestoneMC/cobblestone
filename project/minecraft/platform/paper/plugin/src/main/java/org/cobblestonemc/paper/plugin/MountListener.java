/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;

/**
 * Remembers the tamed mounts each player rides, so they can navigate back to the latest ({@code
 * /navigate mount}). An untamed one is not worth remembering: it will not be waiting where it was.
 * Mounts are horse-like animals ({@link AbstractHorse}: horses, donkeys, mules, llamas, camels, …);
 * pigs, striders and boats are left out on purpose.
 *
 * <p>The record is written when the player gets off, kept current when the mount's chunk unloads
 * (while it is loaded, the destination follows the live entity), and dropped when the mount dies.
 * Writes are persisted off-thread like the rest of Cobblestone's.
 */
final class MountListener implements Listener {

  private final MountDao mounts;
  private final MinecraftScheduler<?> scheduler;

  MountListener(MountDao mounts, MinecraftScheduler<?> scheduler) {
    this.mounts = mounts;
    this.scheduler = scheduler;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDismount(EntityDismountEvent event) {
    if (!(event.getEntity() instanceof Player player)
        || !(event.getDismounted() instanceof AbstractHorse mount)
        || !mount.isTamed()) {
      return;
    }
    Location location = mount.getLocation();
    if (location.getWorld() == null) {
      return;
    }
    MountLocation record =
        new MountLocation(
            player.getUniqueId(),
            mount.getUniqueId(),
            location.getWorld().getKey().asString(),
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ(),
            System.currentTimeMillis());
    scheduler.runAsync(() -> mounts.upsert(record));
  }

  /** Only tamed mounts can be someone's last mount, so only those are worth a write. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onEntitiesUnload(EntitiesUnloadEvent event) {
    for (Entity entity : event.getEntities()) {
      if (!(entity instanceof AbstractHorse mount) || !mount.isTamed()) {
        continue;
      }
      Location location = mount.getLocation();
      if (location.getWorld() == null) {
        continue;
      }
      UUID uuid = mount.getUniqueId();
      String world = location.getWorld().getKey().asString();
      int x = location.getBlockX();
      int y = location.getBlockY();
      int z = location.getBlockZ();
      scheduler.runAsync(() -> mounts.move(uuid, world, x, y, z));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(EntityDeathEvent event) {
    if (event.getEntity() instanceof AbstractHorse mount && mount.isTamed()) {
      UUID uuid = mount.getUniqueId();
      scheduler.runAsync(() -> mounts.remove(uuid));
    }
  }
}
