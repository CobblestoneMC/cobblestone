/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.example.warps;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

/** Small helpers for turning world keys into live {@link World}s and back, with null handling. */
final class Worlds {

  private Worlds() {}

  /** The loaded world with the given key, or {@code null} if the key is malformed or unloaded. */
  static World byKey(String key) {
    NamespacedKey namespacedKey = NamespacedKey.fromString(key);
    return namespacedKey == null ? null : Bukkit.getWorld(namespacedKey);
  }

  /** The namespaced-key string of a location's world (e.g. {@code minecraft:overworld}). */
  static String keyOf(Location location) {
    return location.getWorld().getKey().asString();
  }
}
