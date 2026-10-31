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
import org.geysermc.geyser.api.GeyserApi;

/**
 * Which players are on Bedrock Edition, joining through Geyser, and how to send them a form. Both
 * Geyser and Floodgate are optional: Geyser is asked where it runs on this server, and otherwise
 * Floodgate, which also knows the players of a Geyser on the proxy. Without either, nobody is.
 *
 * <p>Only the implementation found is ever loaded, so neither plugin's classes are needed without
 * it.
 */
sealed interface PaperBedrock {

  /** Geyser's name, as Paper knows it. */
  String GEYSER = "Geyser-Spigot";

  /** Floodgate's name, as Paper knows it. */
  String FLOODGATE = "floodgate";

  /**
   * Finds Geyser or Floodgate. Each ships its own copy of the forms library, so whichever is used
   * must be the one whose copy Cobblestone sees, or the forms built here would not be its forms.
   *
   * @param plugin Cobblestone
   * @return the players' Bedrock-ness, as the plugin found tells it
   */
  static PaperBedrock find(Plugin plugin) {
    Plugin geyser = plugin.getServer().getPluginManager().getPlugin(GEYSER);
    if (geyser != null && sharesForms(geyser)) {
      return new Geyser();
    }
    Plugin floodgate = plugin.getServer().getPluginManager().getPlugin(FLOODGATE);
    if (floodgate != null && sharesForms(floodgate)) {
      return new Floodgate();
    }
    return new None();
  }

  private static boolean sharesForms(Plugin other) {
    try {
      return Class.forName(Form.class.getName(), false, other.getClass().getClassLoader())
          == Form.class;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
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

  /** Neither plugin is here. */
  final class None implements PaperBedrock {
    @Override
    public boolean isBedrock(UUID player) {
      return false;
    }

    @Override
    public void sendForm(UUID player, Form form) {}
  }

  /** Geyser, on this server. */
  final class Geyser implements PaperBedrock {
    @Override
    public boolean isBedrock(UUID player) {
      return GeyserApi.api().isBedrockPlayer(player);
    }

    @Override
    public void sendForm(UUID player, Form form) {
      GeyserApi.api().sendForm(player, form);
    }
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
