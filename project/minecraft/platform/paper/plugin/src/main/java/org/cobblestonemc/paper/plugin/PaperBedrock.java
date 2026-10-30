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
 * Geyser and Floodgate are optional: Floodgate is asked where it is installed, since it also knows
 * the players of a Geyser on the proxy, and otherwise a Geyser on this server. Without either,
 * nobody is.
 *
 * <p>Only the implementation found is ever loaded, so neither plugin's classes are needed without
 * it.
 */
sealed interface PaperBedrock {

  /** The plugins' names, as Paper knows them. */
  String FLOODGATE = "floodgate";

  String GEYSER = "Geyser-Spigot";

  /**
   * Finds Floodgate or Geyser. Each ships its own copy of the forms library, so whichever is used
   * must be the one whose copy Cobblestone sees, or the forms built here would not be its forms.
   *
   * @param plugin Cobblestone
   * @return the players' Bedrock-ness, as the plugin found tells it
   */
  static PaperBedrock find(Plugin plugin) {
    Plugin floodgate = plugin.getServer().getPluginManager().getPlugin(FLOODGATE);
    if (floodgate != null && sharesForms(floodgate)) {
      return new Floodgate();
    }
    Plugin geyser = plugin.getServer().getPluginManager().getPlugin(GEYSER);
    if (geyser != null && sharesForms(geyser)) {
      return new Geyser();
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
   * @return whether it was sent
   */
  boolean sendForm(UUID player, Form form);

  /**
   * Whether Bedrock players can be told apart here at all.
   *
   * @return {@code false} if neither Geyser nor Floodgate was found
   */
  default boolean present() {
    return true;
  }

  /** Neither plugin is here. */
  final class None implements PaperBedrock {
    @Override
    public boolean isBedrock(UUID player) {
      return false;
    }

    @Override
    public boolean sendForm(UUID player, Form form) {
      return false;
    }

    @Override
    public boolean present() {
      return false;
    }
  }

  /** Floodgate, which forwards forms to Geyser wherever it runs. */
  final class Floodgate implements PaperBedrock {
    @Override
    public boolean isBedrock(UUID player) {
      return FloodgateApi.getInstance().isFloodgatePlayer(player);
    }

    @Override
    public boolean sendForm(UUID player, Form form) {
      return FloodgateApi.getInstance().sendForm(player, form);
    }
  }

  /** Geyser, on this server. */
  final class Geyser implements PaperBedrock {
    @Override
    public boolean isBedrock(UUID player) {
      return GeyserApi.api().isBedrockPlayer(player);
    }

    @Override
    public boolean sendForm(UUID player, Form form) {
      return GeyserApi.api().sendForm(player, form);
    }
  }
}
