/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for each player's {@link PlayerPreferences}, one record per player. Implementations
 * must be safe to call from any thread.
 */
public interface PlayerPreferencesDao {

  /**
   * Stores a player's preferences, replacing any stored before.
   *
   * @param preferences the preferences to store
   */
  void upsert(PlayerPreferences preferences);

  /**
   * Looks up a player's preferences.
   *
   * @param player the player
   * @return their preferences, or empty if they have never changed any
   */
  Optional<PlayerPreferences> get(UUID player);
}
