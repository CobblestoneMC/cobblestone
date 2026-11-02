/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;
import org.spongepowered.api.Sponge;
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
import org.spongepowered.api.world.server.ServerWorld;

/**
 * Remembers the tamed mounts each player rides, so they can navigate back to the latest ({@code
 * /navigate mount}). An untamed one is not worth remembering: it will not be waiting where it was.
 * Mounts are horse-like animals ({@link HorseLike}: horses, donkeys, mules, llamas, camels, …);
 * pigs, striders and boats are left out on purpose.
 *
 * <p>The record is written when the player gets off, kept current when the mount's chunk saves its
 * entities (as it does on unload; while it is loaded, the destination follows the live entity) and
 * when the server stops, and dropped when the mount dies. Writes are persisted off-thread like the
 * rest of Cobblestone's.
 */
final class SpongeMountListener {

  private final MountDao mounts;
  private final Executor data;

  /** Set once the server is stopping and every mount has been saved; nothing is written after. */
  private volatile boolean stopped;

  SpongeMountListener(MountDao mounts, Executor data) {
    this.mounts = mounts;
    this.data = data;
  }

  /** Records the tamed mount a player just got off. */
  @Listener(order = Order.POST)
  public void onDismount(RideEntityEvent.Dismount event) {
    Optional<ServerPlayer> rider = event.cause().first(ServerPlayer.class);
    if (stopped || rider.isEmpty() || !(event.entity() instanceof HorseLike mount)) {
      return;
    }
    MountLocation record = ridden(rider.get(), mount);
    if (record != null) {
      data.execute(() -> mounts.upsert(record));
    }
  }

  /** Only tamed mounts can be someone's last mount, so only those are worth a write. */
  @Listener(order = Order.POST)
  public void onEntitiesSave(ChunkEvent.Entities.Save.Pre event) {
    if (stopped) {
      return;
    }
    for (Entity entity : event.chunk().entities()) {
      if (entity instanceof HorseLike mount) {
        Runnable move = moved(mount);
        if (move != null) {
          data.execute(move);
        }
      }
    }
  }

  /** Forgets a mount that died. */
  @Listener(order = Order.POST)
  public void onDeath(DestructEntityEvent.Death event) {
    if (!stopped
        && event.entity() instanceof HorseLike mount
        && mount.get(Keys.IS_TAMED).orElse(false)) {
      UUID uuid = mount.uniqueId();
      data.execute(() -> mounts.remove(uuid));
    }
  }

  /**
   * Saves, on this thread, where every loaded tamed mount is, and records the mount each player is
   * riding as theirs; then stops listening. For the server stopping: players are kicked and worlds
   * saved only after Cobblestone has stopped its async workers and closed its data store.
   */
  void saveLoaded() {
    stopped = true;
    for (ServerPlayer player : Sponge.server().onlinePlayers()) {
      if (player.get(Keys.VEHICLE).orElse(null) instanceof HorseLike mount) {
        MountLocation record = ridden(player, mount);
        if (record != null) {
          mounts.upsert(record);
        }
      }
    }
    for (ServerWorld world : Sponge.server().worldManager().worlds()) {
      for (Entity entity : world.entities()) {
        if (entity instanceof HorseLike mount) {
          Runnable move = moved(mount);
          if (move != null) {
            move.run();
          }
        }
      }
    }
  }

  /** The record of a player having ridden a mount, or {@code null} if it is not worth keeping. */
  private static MountLocation ridden(ServerPlayer player, HorseLike mount) {
    if (!mount.get(Keys.IS_TAMED).orElse(false)) {
      return null;
    }
    ServerLocation location = mount.serverLocation();
    return new MountLocation(
        player.uniqueId(),
        mount.uniqueId(),
        location.worldKey().asString(),
        location.blockX(),
        location.blockY(),
        location.blockZ(),
        System.currentTimeMillis());
  }

  /** The write moving a mount's record to where it is, or {@code null} if it has none to move. */
  private Runnable moved(HorseLike mount) {
    if (!mount.get(Keys.IS_TAMED).orElse(false)) {
      return null;
    }
    ServerLocation location = mount.serverLocation();
    UUID uuid = mount.uniqueId();
    String world = location.worldKey().asString();
    int x = location.blockX();
    int y = location.blockY();
    int z = location.blockZ();
    return () -> mounts.move(uuid, world, x, y, z);
  }
}
