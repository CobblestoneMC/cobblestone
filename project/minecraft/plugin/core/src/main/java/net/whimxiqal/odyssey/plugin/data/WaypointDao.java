/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for player and global {@link Waypoint}s. A waypoint is keyed by {@code (owner,
 * name)}; an empty owner is the global scope. Implementations must be safe to call from any thread.
 */
public interface WaypointDao {

  /**
   * Stores a waypoint, overwriting any existing one with the same {@code (owner, name)}.
   *
   * @param waypoint the waypoint to store
   */
  void put(Waypoint waypoint);

  /**
   * Removes the waypoint with the given owner and name.
   *
   * @param owner the owner (empty for a global waypoint)
   * @param name the name
   * @return {@code true} if a waypoint existed and was removed
   */
  boolean remove(Optional<UUID> owner, String name);

  /**
   * Looks up a single waypoint by owner and name.
   *
   * @param owner the owner (empty for a global waypoint)
   * @param name the name
   * @return the waypoint, or empty if none exists
   */
  Optional<Waypoint> get(Optional<UUID> owner, String name);

  /**
   * Returns every personal waypoint owned by the given player.
   *
   * @param owner the owning player
   * @return the player's waypoints (never {@code null}; empty if none)
   */
  List<Waypoint> ownedBy(UUID owner);

  /**
   * Returns every global (server-wide) waypoint.
   *
   * @return the global waypoints (never {@code null}; empty if none)
   */
  List<Waypoint> global();
}
