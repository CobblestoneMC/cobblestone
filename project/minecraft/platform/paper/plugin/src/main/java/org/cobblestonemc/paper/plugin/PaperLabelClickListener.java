/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.cobblestonemc.plugin.hologram.TrailLabelManager;

/**
 * Hands every left click to the trail labels, which say whether it hit one; such a click does
 * nothing else. A left click reaches the server as a click on the air, on a block or on an entity,
 * so all three are watched. Labels are hit wherever they are, not only within the player's reach.
 */
final class PaperLabelClickListener implements Listener {

  private final TrailLabelManager<Entity, PaperTripAgent, ?> labels;

  PaperLabelClickListener(TrailLabelManager<Entity, PaperTripAgent, ?> labels) {
    this.labels = labels;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onClick(PlayerInteractEvent event) {
    Action action = event.getAction();
    if ((action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)
        && hit(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onAttack(PrePlayerAttackEntityEvent event) {
    if (hit(event.getPlayer())) {
      event.setCancelled(true);
    }
  }

  private boolean hit(Player player) {
    return labels.swing(player.getUniqueId());
  }
}
