/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.destination;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;

/**
 * A plain, immutable {@link MinecraftDestination}. Platform-neutral so every platform plugin and
 * built-in provider (waypoints now; portals, integrations later) can surface targets without a
 * bespoke class.
 *
 * @param destination the core navigation goal
 * @param displayName the human-facing name
 * @param permissions the permission nodes required to use it (all of them); empty for unrestricted
 * @param mobile whether the destination can move (see {@link MinecraftDestination#isMobile()})
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public record SimpleMinecraftDestination<W, V>(
    Destination<WorldRegion<W, V>> destination,
    Component displayName,
    List<String> permissions,
    boolean mobile)
    implements MinecraftDestination<W, V> {

  /** Canonical constructor; defensively copies the permission list. */
  public SimpleMinecraftDestination {
    permissions = List.copyOf(permissions);
  }

  /**
   * Creates a stationary destination.
   *
   * @param destination the goal
   * @param displayName the name
   * @param permissions the required permissions
   */
  public SimpleMinecraftDestination(
      Destination<WorldRegion<W, V>> destination, Component displayName, List<String> permissions) {
    this(destination, displayName, permissions, false);
  }

  @Override
  public boolean isMobile() {
    return mobile;
  }
}
