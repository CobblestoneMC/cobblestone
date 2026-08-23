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
 * A persisted, platform-neutral navigation target owned by a player or the server.
 *
 * <p>Storage keeps only a world key and integer block coordinates so the record survives across
 * restarts and platforms; the plugin layer re-hydrates it into a live {@code MinecraftDestination}
 * when a search is requested. A location is identified by {@code (owner, name)}: an empty {@link
 * #owner()} denotes a server-wide ("global") location visible to everyone.
 *
 * @param owner the owning player, or empty for a global location
 * @param name the case-sensitive name (unique within an owner's scope)
 * @param world the namespaced world key (e.g. {@code minecraft:overworld})
 * @param x the block x-coordinate
 * @param y the block y-coordinate
 * @param z the block z-coordinate
 */
public record Location(Optional<UUID> owner, String name, String world, int x, int y, int z) {

  /**
   * Creates a personal location.
   *
   * @param owner the owning player
   * @param name the name
   * @param world the world key
   * @param x the block x
   * @param y the block y
   * @param z the block z
   * @return the location
   */
  public static Location personal(UUID owner, String name, String world, int x, int y, int z) {
    return new Location(Optional.of(owner), name, world, x, y, z);
  }

  /**
   * Creates a global (server-wide) location.
   *
   * @param name the name
   * @param world the world key
   * @param x the block x
   * @param y the block y
   * @param z the block z
   * @return the location
   */
  public static Location global(String name, String world, int x, int y, int z) {
    return new Location(Optional.empty(), name, world, x, y, z);
  }

  /**
   * Returns whether this is a global (server-wide) location.
   *
   * @return {@code true} if global
   */
  public boolean isGlobal() {
    return owner.isEmpty();
  }
}
