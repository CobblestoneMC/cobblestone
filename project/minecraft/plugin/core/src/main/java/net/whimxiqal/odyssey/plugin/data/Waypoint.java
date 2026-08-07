/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import java.util.Optional;
import java.util.UUID;

/**
 * A persisted, platform-neutral navigation target owned by a player or the server.
 *
 * <p>Storage keeps only a world key and integer block coordinates so the record survives across
 * restarts and platforms; the plugin layer re-hydrates it into a live {@code MinecraftDestination}
 * when a search is requested. A waypoint is identified by {@code (owner, name)}: an empty
 * {@link #owner()} denotes a server-wide ("global") waypoint visible to everyone.
 *
 * @param owner the owning player, or empty for a global waypoint
 * @param name the case-sensitive name (unique within an owner's scope)
 * @param world the namespaced world key (e.g. {@code minecraft:overworld})
 * @param x the block x-coordinate
 * @param y the block y-coordinate
 * @param z the block z-coordinate
 */
public record Waypoint(Optional<UUID> owner, String name, String world, int x, int y, int z) {

  /**
   * Creates a personal waypoint.
   *
   * @param owner the owning player
   * @param name the name
   * @param world the world key
   * @param x the block x
   * @param y the block y
   * @param z the block z
   * @return the waypoint
   */
  public static Waypoint personal(UUID owner, String name, String world, int x, int y, int z) {
    return new Waypoint(Optional.of(owner), name, world, x, y, z);
  }

  /**
   * Creates a global (server-wide) waypoint.
   *
   * @param name the name
   * @param world the world key
   * @param x the block x
   * @param y the block y
   * @param z the block z
   * @return the waypoint
   */
  public static Waypoint global(String name, String world, int x, int y, int z) {
    return new Waypoint(Optional.empty(), name, world, x, y, z);
  }

  /**
   * Returns whether this is a global (server-wide) waypoint.
   *
   * @return {@code true} if global
   */
  public boolean isGlobal() {
    return owner.isEmpty();
  }
}
