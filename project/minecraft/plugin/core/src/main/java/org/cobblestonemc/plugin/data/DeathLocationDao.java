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
 * Persistence for each player's last {@link DeathLocation}, keyed by player. Only the most recent
 * death is kept, so {@link #upsert} replaces whatever was there. Implementations must be safe to
 * call from any thread.
 */
public interface DeathLocationDao {

  /**
   * Stores a player's death location, replacing their previous one.
   *
   * @param location the death location to store
   */
  void upsert(DeathLocation location);

  /**
   * Looks up where a player last died.
   *
   * @param player the player
   * @return the death location, or empty if the player has not died since tracking began
   */
  Optional<DeathLocation> get(UUID player);
}
