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
 * Persistence for the {@link MountLocation mounts} each player has ridden. Only a player's most
 * recently ridden mount is kept for now. Implementations must be safe to call from any thread.
 */
public interface MountDao {

  /**
   * Records that a player has just got off a mount: stores it as their newest, replacing any
   * earlier record of that same mount, and forgets their older ones beyond those that are kept.
   *
   * @param location the mount, where it is, and when it was ridden
   */
  void upsert(MountLocation location);

  /**
   * Looks up the mount a player rode most recently, of those still known to be alive.
   *
   * @param player the player
   * @return the mount's record, or empty if the player has not ridden one since tracking began, or
   *     every one they have is dead
   */
  Optional<MountLocation> get(UUID player);

  /**
   * Records that a mount has moved, for every player who has ridden it.
   *
   * @param mount the mount's entity id
   * @param world its namespaced world key
   * @param x its block x-coordinate
   * @param y its block y-coordinate
   * @param z its block z-coordinate
   */
  void move(UUID mount, String world, int x, int y, int z);

  /**
   * Forgets a mount that no longer exists (it died), for every player who has ridden it.
   *
   * @param mount the mount's entity id
   */
  void remove(UUID mount);
}
