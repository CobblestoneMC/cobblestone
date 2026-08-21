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
 * Persistence for player and global {@link Location}s. A location is keyed by {@code (owner,
 * name)}; an empty owner is the global scope. Implementations must be safe to call from any thread.
 */
public interface LocationDao {

  /**
   * Stores a location, overwriting any existing one with the same {@code (owner, name)}.
   *
   * @param location the location to store
   */
  void put(Location location);

  /**
   * Removes the location with the given owner and name.
   *
   * @param owner the owner (empty for a global location)
   * @param name the name
   * @return {@code true} if a location existed and was removed
   */
  boolean remove(Optional<UUID> owner, String name);

  /**
   * Looks up a single location by owner and name.
   *
   * @param owner the owner (empty for a global location)
   * @param name the name
   * @return the location, or empty if none exists
   */
  Optional<Location> get(Optional<UUID> owner, String name);

  /**
   * Returns every personal location owned by the given player.
   *
   * @param owner the owning player
   * @return the player's locations (never {@code null}; empty if none)
   */
  List<Location> ownedBy(UUID owner);

  /**
   * Returns every global (server-wide) location.
   *
   * @return the global locations (never {@code null}; empty if none)
   */
  List<Location> global();
}
