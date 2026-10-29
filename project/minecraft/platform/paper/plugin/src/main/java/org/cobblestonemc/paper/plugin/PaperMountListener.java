/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;

/**
 * Remembers the tamed mounts each player rides, so they can navigate back to the latest ({@code
 * /navigate mount}). An untamed one is not worth remembering: it will not be waiting where it was.
 * Mounts are horse-like animals ({@link AbstractHorse}: horses, donkeys, mules, llamas, camels, …);
 * pigs, striders and boats are left out on purpose.
 *
 * <p>The record is written when the player gets off, kept current when the mount's chunk unloads
 * and when the server stops (while it is loaded, the destination follows the live entity), and
 * dropped when the mount dies. Writes are persisted off-thread like the rest of Cobblestone's.
 */
final class PaperMountListener implements Listener {

  private final MountDao mounts;
  private final Executor data;

  PaperMountListener(MountDao mounts, Executor data) {
    this.mounts = mounts;
    this.data = data;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDismount(EntityDismountEvent event) {
    if (event.getEntity() instanceof Player player
        && event.getDismounted() instanceof AbstractHorse mount) {
      MountLocation record = ridden(player, mount);
      if (record != null) {
        data.execute(() -> mounts.upsert(record));
      }
    }
  }

  /** Only tamed mounts can be someone's last mount, so only those are worth a write. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onEntitiesUnload(EntitiesUnloadEvent event) {
    for (Entity entity : event.getEntities()) {
      if (entity instanceof AbstractHorse mount) {
        Runnable move = moved(mount);
        if (move != null) {
          data.execute(move);
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDeath(EntityDeathEvent event) {
    if (event.getEntity() instanceof AbstractHorse mount && mount.isTamed()) {
      UUID uuid = mount.getUniqueId();
      data.execute(() -> mounts.remove(uuid));
    }
  }

  /**
   * Saves, on this thread, where every loaded tamed mount is, and records the mount each player is
   * riding as theirs. For the server stopping: plugins are disabled before players are kicked and
   * worlds unload, so neither a dismount nor an unload will be heard, and the async workers have
   * stopped.
   */
  void saveLoaded() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (player.getVehicle() instanceof AbstractHorse mount) {
        MountLocation record = ridden(player, mount);
        if (record != null) {
          mounts.upsert(record);
        }
      }
    }
    for (World world : Bukkit.getWorlds()) {
      for (AbstractHorse mount : world.getEntitiesByClass(AbstractHorse.class)) {
        Runnable move = moved(mount);
        if (move != null) {
          move.run();
        }
      }
    }
  }

  /** The record of a player having ridden a mount, or {@code null} if it is not worth keeping. */
  private static MountLocation ridden(Player player, AbstractHorse mount) {
    Location location = mount.getLocation();
    if (!mount.isTamed() || location.getWorld() == null) {
      return null;
    }
    return new MountLocation(
        player.getUniqueId(),
        mount.getUniqueId(),
        location.getWorld().getKey().asString(),
        location.getBlockX(),
        location.getBlockY(),
        location.getBlockZ(),
        System.currentTimeMillis());
  }

  /** The write moving a mount's record to where it is, or {@code null} if it has none to move. */
  private Runnable moved(AbstractHorse mount) {
    Location location = mount.getLocation();
    if (!mount.isTamed() || location.getWorld() == null) {
      return null;
    }
    UUID uuid = mount.getUniqueId();
    String world = location.getWorld().getKey().asString();
    int x = location.getBlockX();
    int y = location.getBlockY();
    int z = location.getBlockZ();
    return () -> mounts.move(uuid, world, x, y, z);
  }
}
