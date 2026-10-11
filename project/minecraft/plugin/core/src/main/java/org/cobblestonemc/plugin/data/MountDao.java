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
 * Persistence for each player's last {@link MountLocation}, keyed by player. Implementations must
 * be safe to call from any thread.
 */
public interface MountDao {

  /**
   * Stores the mount a player last rode, replacing their previous one.
   *
   * @param location the mount and where it is
   */
  void upsert(MountLocation location);

  /**
   * Looks up the mount a player last rode.
   *
   * @param player the player
   * @return the mount's record, or empty if the player has not ridden one since tracking began
   */
  Optional<MountLocation> get(UUID player);

  /**
   * Records that a mount has moved, for every player whose last mount it is.
   *
   * @param mount the mount's entity id
   * @param world its namespaced world key
   * @param x its block x-coordinate
   * @param y its block y-coordinate
   * @param z its block z-coordinate
   */
  void move(UUID mount, String world, int x, int y, int z);

  /**
   * Forgets a mount that no longer exists (it died), for every player whose last mount it was.
   *
   * @param mount the mount's entity id
   */
  void remove(UUID mount);
}
