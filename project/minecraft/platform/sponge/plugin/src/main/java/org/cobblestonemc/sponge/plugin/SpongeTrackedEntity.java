/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.cobblestonemc.plugin.trip.TripTarget;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;

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
final class SpongeTrackedEntity {

  /** How far above the top of an entity its name tag floats, in blocks. */
  private static final double NAME_TAG_RISE = 0.5;

  private final UUID uuid;

  // Written only on the main thread and read by any thread, search threads included. Volatile is
  // all the guarding they need: each is replaced whole (a location is immutable). Only anchor()
  // reads two together, the location and the height; a mismatched pair puts the label off by one
  // refresh's movement until the next read.
  private volatile Entity lastSeen;
  private volatile ServerLocation lastLocation;
  private volatile double lastHeight;

  SpongeTrackedEntity(UUID uuid) {
    this.uuid = uuid;
  }

  /**
   * Where the entity is, as of the last look on the main thread (taken now, if this is it).
   *
   * @return its location, or empty if it has not been seen loaded
   */
  Optional<ServerLocation> location() {
    refresh();
    return Optional.ofNullable(lastLocation);
  }

  /**
   * Where a label naming the entity floats: just above it, where a name tag would be.
   *
   * @return the point, or empty if it has not been seen loaded
   */
  Optional<TripTarget.Point> anchor() {
    return location()
        .map(
            at ->
                new TripTarget.Point(
                    at.world().key().asString(),
                    new Vec3(at.x(), at.y() + lastHeight + NAME_TAG_RISE, at.z())));
  }

  /**
   * Whether the entity is still alive: {@code false} once it has died. One that is merely unloaded
   * counts as alive, since it cannot die while unloaded. Unloading and reloading it makes a new
   * entity object, so the latest one seen is kept; once killed, that object reports no health even
   * after it is removed from the world.
   *
   * @return {@code false} once it has died
   */
  boolean alive() {
    refresh();
    Entity seen = lastSeen;
    return seen == null || seen.get(Keys.HEALTH).map(health -> health > 0).orElse(true);
  }

  private void refresh() {
    if (!Sponge.server().onMainThread()) {
      return;
    }
    // Prefer a copy still in its world, in case an old one lingers in a world it has left.
    Entity removed = null;
    for (ServerWorld world : Sponge.server().worldManager().worlds()) {
      Optional<Entity> entity = world.entity(uuid);
      if (entity.isEmpty()) {
        continue;
      }
      if (!entity.get().isRemoved()) {
        lastSeen = entity.get();
        lastHeight = entity.get().boundingBox().map(box -> box.size().y()).orElse(0.0);
        lastLocation = entity.get().serverLocation();
        return;
      }
      removed = entity.get();
    }
    if (removed != null) {
      lastSeen = removed;
    }
  }
}
