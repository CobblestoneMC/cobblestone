/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import net.kyori.adventure.audience.Audience;
import net.whimxiqal.odyssey.plugin.api.NavigatorContext;
import org.bukkit.entity.Player;

/**
 * The Paper {@link NavigatorContext}: a Bukkit {@link Player} is itself an Adventure {@link
 * Audience}, so both accessors return the same object.
 *
 * @param player the guided player
 */
public record PaperNavigatorContext(Player player) implements NavigatorContext<Player> {

  @Override
  public Audience audience() {
    return player;
  }
}
