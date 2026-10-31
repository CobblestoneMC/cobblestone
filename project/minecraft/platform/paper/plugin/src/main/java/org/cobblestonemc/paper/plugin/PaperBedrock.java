/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.Form;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Which players are on Bedrock Edition, as Floodgate tells it, and how to send them a form.
 * Floodgate is optional: without it, nobody is, and its classes are never loaded.
 */
sealed interface PaperBedrock {

  /**
   * Finds Floodgate.
   *
   * @param plugin Cobblestone
   * @return the players' Bedrock-ness
   */
  static PaperBedrock find(Plugin plugin) {
    return plugin.getServer().getPluginManager().getPlugin("floodgate") != null
        ? new Floodgate()
        : new None();
  }

  /**
   * Whether a player is on Bedrock Edition.
   *
   * @param player the player's unique id
   * @return {@code true} if they joined through Geyser
   */
  boolean isBedrock(UUID player);

  /**
   * Sends a Bedrock player a form.
   *
   * @param player the player's unique id
   * @param form the form
   */
  void sendForm(UUID player, Form form);

  /** Floodgate is not here. */
  final class None implements PaperBedrock {
    @Override
    public boolean isBedrock(UUID player) {
      return false;
    }

    @Override
    public void sendForm(UUID player, Form form) {}
  }

  /** Floodgate, which forwards forms to Geyser wherever it runs. */
  final class Floodgate implements PaperBedrock {
    @Override
    public boolean isBedrock(UUID player) {
      return FloodgateApi.getInstance().isFloodgatePlayer(player);
    }

    @Override
    public void sendForm(UUID player, Form form) {
      FloodgateApi.getInstance().sendForm(player, form);
    }
  }
}
