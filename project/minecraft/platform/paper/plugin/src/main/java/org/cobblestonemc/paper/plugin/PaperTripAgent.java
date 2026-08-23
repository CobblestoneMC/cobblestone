/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.UUID;
import org.bukkit.entity.Entity;
import org.cobblestonemc.plugin.trip.TripAgent;

public class PaperTripAgent implements TripAgent<Entity> {

  private final Entity entity;

  public PaperTripAgent(Entity entity) {
    this.entity = entity;
  }

  @Override
  public UUID uuid() {
    return entity.getUniqueId();
  }

  @Override
  public Entity entity() {
    return entity;
  }
}
