/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import org.cobblestonemc.plugin.trip.TripAgent;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;

/** A {@link TripAgent} wrapping the guided {@link ServerPlayer}. */
public final class SpongeTripAgent implements TripAgent<Entity> {

  private final ServerPlayer player;

  public SpongeTripAgent(ServerPlayer player) {
    this.player = player;
  }

  /**
   * Returns the guided player.
   *
   * @return the player
   */
  public ServerPlayer player() {
    return player;
  }

  @Override
  public UUID uuid() {
    return player.uniqueId();
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
