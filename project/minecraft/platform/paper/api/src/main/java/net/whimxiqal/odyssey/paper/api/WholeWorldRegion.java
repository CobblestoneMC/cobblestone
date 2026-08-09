/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.api;

import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.joml.Vector3i;

/**
 * A region that spans an entire world — every cell in it is contained. Used to navigate "to a world":
 * the search succeeds as soon as it arrives there (e.g. through a portal). Holds only the world's
 * key and re-resolves the {@link World} on demand, so it never pins a possibly-unloaded world object.
 */
public final class WholeWorldRegion implements WorldRegion<World, Vector3i> {

  private final String worldKey;

  /**
   * Creates a region for the world with the given namespaced key.
   *
   * @param worldKey the world's namespaced key (e.g. {@code minecraft:the_nether})
   */
  public WholeWorldRegion(String worldKey) {
    this.worldKey = worldKey;
  }

  @Override
  public World world() {
    NamespacedKey key = NamespacedKey.fromString(worldKey);
    return key == null ? null : Bukkit.getWorld(key);
  }

  @Override
  public boolean contains(Vector3i vector) {
    return true;
  }

  @Override
  public Vector3i nearestBoundaryLocation(Vector3i vector) {
    return vector; // already inside; nothing to clamp
  }

  @Override
  public String toString() {
    return "WholeWorld[" + worldKey + "]";
  }
}
