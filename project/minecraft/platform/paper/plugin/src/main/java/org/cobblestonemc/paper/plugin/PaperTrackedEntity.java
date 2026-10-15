/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.cobblestonemc.minecraft.MinecraftScheduler;

/**
 * An entity a destination follows — a mount — read safely from any thread.
 *
 * <p>Entities may only be looked up on a server thread, and (on Folia) only read by the region that
 * owns them, but a live trip's re-search asks for the destination's location from a search thread.
 * So the entity is looked up only on a server thread — when the command runs, and each time the
 * trip checks that its target {@linkplain #alive() still exists}, about once a second — and its
 * location is read there if that thread owns it, or else by a task on the entity's own scheduler.
 * Anywhere else gets the location last read.
 *
 * <p>Nothing is looked up on construction, since destinations are built while tab-completing.
 */
final class PaperTrackedEntity {

  private final UUID uuid;
  private final MinecraftScheduler<Entity> scheduler;
  private volatile Entity lastSeen;
  private volatile Location lastLocation;

  PaperTrackedEntity(UUID uuid, MinecraftScheduler<Entity> scheduler) {
    this.uuid = uuid;
    this.scheduler = scheduler;
  }

  /**
   * Where the entity is, as of the last read (taken now, if this thread may).
   *
   * @return its location, or empty if it has not been seen loaded
   */
  Optional<Location> location() {
    refresh();
    Location location = lastLocation;
    return location == null ? Optional.empty() : Optional.of(location.clone());
  }

  /**
   * Whether the entity is still alive: {@code false} once it has died. One that is merely unloaded
   * counts as alive, since it cannot die while unloaded. Unloading and reloading it makes a new
   * Bukkit entity, so the latest one seen is kept; once killed, that entity reports no health even
   * after it is removed from the world.
   *
   * @return {@code false} once it has died
   */
  boolean alive() {
    refresh();
    return !(lastSeen instanceof LivingEntity living && living.getHealth() <= 0);
  }

  private void refresh() {
    if (!Bukkit.isPrimaryThread()) {
      return;
    }
    Entity current = Bukkit.getEntity(uuid);
    if (current == null) {
      return;
    }
    lastSeen = current;
    if (Bukkit.isOwnedByCurrentRegion(current)) {
      read(current);
    } else {
      scheduler.runAtEntity(current, () -> read(current));
    }
  }

  private void read(Entity entity) {
    if (entity.isValid()) {
      lastLocation = entity.getLocation();
    }
  }
}
