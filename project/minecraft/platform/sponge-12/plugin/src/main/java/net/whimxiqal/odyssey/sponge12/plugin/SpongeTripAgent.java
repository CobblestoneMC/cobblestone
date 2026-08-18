/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import java.util.UUID;
import net.whimxiqal.odyssey.plugin.trip.TripAgent;
import org.spongepowered.api.entity.Entity;

/** A {@link TripAgent} wrapping a Sponge {@link Entity} (usually the guided player). */
public final class SpongeTripAgent implements TripAgent<Entity> {

  private final Entity entity;

  public SpongeTripAgent(Entity entity) {
    this.entity = entity;
  }

  @Override
  public UUID uuid() {
    return entity.uniqueId();
  }

  @Override
  public Entity entity() {
    return entity;
  }
}
