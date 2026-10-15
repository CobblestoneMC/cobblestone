/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Stamps each newly tamed animal with when it was tamed, on the entity itself, so that pets sharing
 * a name are numbered oldest first in {@code /navigate pet} ({@code Rex}, {@code Rex-2}, …). The
 * game does not remember an entity's age across chunk loads, so this is the only stable "how old"
 * there is.
 */
final class PaperPetListener implements Listener {

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onTame(EntityTameEvent event) {
    event
        .getEntity()
        .getPersistentDataContainer()
        .set(BuiltInDestinations.TAMED_AT, PersistentDataType.LONG, System.currentTimeMillis());
  }
}
