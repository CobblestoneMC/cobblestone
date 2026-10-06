/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.UUID;

/**
 * The display choices one player has made for themselves.
 *
 * @param player the player
 * @param sidebar whether their active trips are listed in the sidebar
 */
public record PlayerPreferences(UUID player, boolean sidebar) {

  /**
   * The preferences of a player who has never changed any.
   *
   * @param player the player
   * @return the defaults
   */
  public static PlayerPreferences defaults(UUID player) {
    return new PlayerPreferences(player, true);
  }
}
