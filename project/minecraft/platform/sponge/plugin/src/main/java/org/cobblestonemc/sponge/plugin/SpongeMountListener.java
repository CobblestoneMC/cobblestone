/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.animal.horse.HorseLike;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.entity.DestructEntityEvent;
import org.spongepowered.api.event.entity.RideEntityEvent;
import org.spongepowered.api.event.world.chunk.ChunkEvent;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * Remembers the mount each player last rode, so they can navigate back to it ({@code /navigate
 * mount}). Mounts are horse-like animals ({@link HorseLike}: horses, donkeys, mules, llamas,
 * camels, …); pigs, striders and boats are left out on purpose.
 *
 * <p>The record is written when the player gets off, kept current when the mount's chunk saves its
 * entities (as it does on unload; while it is loaded, the destination follows the live entity), and
 * dropped when the mount dies. Writes are persisted off-thread like the rest of Cobblestone's.
 */
final class SpongeMountListener {

  private final MountDao mounts;
  private final MinecraftScheduler<?> scheduler;

  SpongeMountListener(MountDao mounts, MinecraftScheduler<?> scheduler) {
    this.mounts = mounts;
    this.scheduler = scheduler;
  }

  /** Records the mount a player just got off. */
  @Listener(order = Order.POST)
  public void onDismount(RideEntityEvent.Dismount event) {
    Optional<ServerPlayer> rider = event.cause().first(ServerPlayer.class);
    if (rider.isEmpty() || !(event.entity() instanceof HorseLike mount)) {
      return;
    }
    ServerLocation location = mount.serverLocation();
    MountLocation record =
        new MountLocation(
            rider.get().uniqueId(),
            mount.uniqueId(),
            location.worldKey().asString(),
            location.blockX(),
            location.blockY(),
            location.blockZ());
    scheduler.runAsync(() -> mounts.upsert(record));
  }

  /** Only tamed mounts can be someone's last mount, so only those are worth a write. */
  @Listener(order = Order.POST)
  public void onEntitiesSave(ChunkEvent.Entities.Save.Pre event) {
    for (Entity entity : event.chunk().entities()) {
      if (!(entity instanceof HorseLike mount) || !mount.get(Keys.IS_TAMED).orElse(false)) {
        continue;
      }
      ServerLocation location = mount.serverLocation();
      UUID uuid = mount.uniqueId();
      String world = location.worldKey().asString();
      int x = location.blockX();
      int y = location.blockY();
      int z = location.blockZ();
      scheduler.runAsync(() -> mounts.move(uuid, world, x, y, z));
    }
  }

  /** Forgets a mount that died. */
  @Listener(order = Order.POST)
  public void onDeath(DestructEntityEvent.Death event) {
    if (event.entity() instanceof HorseLike mount && mount.get(Keys.IS_TAMED).orElse(false)) {
      UUID uuid = mount.uniqueId();
      scheduler.runAsync(() -> mounts.remove(uuid));
    }
  }
}
