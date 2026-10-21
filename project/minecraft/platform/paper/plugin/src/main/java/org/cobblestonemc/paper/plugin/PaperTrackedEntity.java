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
import org.cobblestonemc.plugin.navigator.Vec3;
import org.cobblestonemc.plugin.trip.TripTarget;

/**
 * An entity a destination follows — a pet or a mount — read safely from any thread.
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

  /** How far above the top of an entity its name tag floats, in blocks. */
  private static final double NAME_TAG_RISE = 0.5;

  private final UUID uuid;
  private final MinecraftScheduler<Entity> scheduler;

  // Written by server threads (the command's, the trip's, or a task on the entity's scheduler) and
  // read by any thread, search threads included. Volatile is all the guarding they need: each is
  // replaced whole, never changed in place (a location is a fresh copy, and cloned again for the
  // reader), and the two are never needed together. Two writers racing can leave the older read
  // last, but the next refresh, about a second later, corrects it. alive() reads the last-seen
  // entity's health from the trip's thread, which on Folia may not own it; a stale read there only
  // ends the trip a check later.
  private volatile Entity lastSeen;
  private volatile Location lastLocation;
  private volatile double lastHeight;

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
   * Where a label naming the entity floats: just above it, where a name tag would be.
   *
   * @return the point, or empty if it has not been seen loaded
   */
  Optional<TripTarget.Point> labelPoint() {
    return location()
        .map(
            at ->
                new TripTarget.Point(
                    at.getWorld().getKey().asString(),
                    new Vec3(at.getX(), at.getY() + lastHeight + NAME_TAG_RISE, at.getZ())));
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
      lastHeight = entity.getHeight();
      lastLocation = entity.getLocation();
    }
  }
}
