/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import org.cobblestonemc.plugin.hologram.TrailLabelManager;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.block.InteractBlockEvent;
import org.spongepowered.api.event.entity.InteractEntityEvent;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.item.inventory.InteractItemEvent;

/**
 * Hands every left click to the trail labels, which say whether it hit one; such a click does
 * nothing else where it can be stopped. A left click reaches the server as a click on the air, on a
 * block or on an entity, so all three are watched. Labels are hit wherever they are, not only
 * within the player's reach.
 */
final class SpongeLabelClickListener {

  private final TrailLabelManager<Entity, SpongeTripAgent, ?> labels;

  SpongeLabelClickListener(TrailLabelManager<Entity, SpongeTripAgent, ?> labels) {
    this.labels = labels;
  }

  @Listener(order = Order.FIRST)
  public void onClickAir(InteractItemEvent.Primary event, @First ServerPlayer player) {
    hit(player); // a click on the air can't be cancelled, and does nothing anyway
  }

  @Listener(order = Order.FIRST)
  public void onClickBlock(InteractBlockEvent.Primary.Start event, @First ServerPlayer player) {
    if (hit(player)) {
      event.setCancelled(true);
    }
  }

  @Listener(order = Order.FIRST)
  public void onClickEntity(InteractEntityEvent.Primary event, @First ServerPlayer player) {
    if (hit(player)) {
      event.setCancelled(true);
    }
  }

  private boolean hit(ServerPlayer player) {
    return labels.swing(player.uniqueId());
  }
}
