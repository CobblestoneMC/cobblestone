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

/**
 * An entity a destination follows — a pet or a mount — read safely from any thread.
 *
 * <p>Entities may only be looked up on the main thread, but a live trip's re-search asks for the
 * destination's location off it. So the entity is looked up only when on the main thread — when the
 * command runs, and each time the trip checks that its target {@linkplain #alive() still exists},
 * about once a second — and anywhere else gets the location seen then.
 *
 * <p>Nothing is looked up on construction, since destinations are built while tab-completing.
 */
final class TrackedEntity {

  private final UUID uuid;
  private volatile Entity lastSeen;
  private volatile Location lastLocation;

  TrackedEntity(UUID uuid) {
    this.uuid = uuid;
  }

  /**
   * Where the entity is, as of the last look on the main thread (taken now, if this is it).
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
    if (current.isValid()) {
      lastLocation = current.getLocation();
    }
  }
}
