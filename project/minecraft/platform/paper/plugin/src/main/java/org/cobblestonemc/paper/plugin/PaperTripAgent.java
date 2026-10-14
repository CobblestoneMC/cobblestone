/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.cobblestonemc.plugin.trip.TripAgent;

/** A {@link TripAgent} wrapping the guided {@link Player}. */
public final class PaperTripAgent implements TripAgent<Entity> {

  private final Player player;

  public PaperTripAgent(Player player) {
    this.player = player;
  }

  /**
   * Returns the guided player.
   *
   * @return the player
   */
  public Player player() {
    return player;
  }

  @Override
  public UUID uuid() {
    return player.getUniqueId();
  }

  @Override
  public Entity entity() {
    return player;
  }

  @Override
  public Audience audience() {
    return player;
  }

  @Override
  public Locale locale() {
    return player.locale();
  }
}
