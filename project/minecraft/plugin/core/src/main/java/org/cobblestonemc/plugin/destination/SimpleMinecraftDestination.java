/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.MinecraftDestination;

/**
 * A plain, immutable {@link MinecraftDestination}. Platform-neutral so every platform plugin and
 * built-in provider (locations now; portals, integrations later) can surface targets without a
 * bespoke class.
 *
 * @param destination the core navigation goal
 * @param displayName the human-facing name
 * @param permissions the permission nodes required to use it (all of them); empty for unrestricted
 * @param mobile whether the destination can move (see {@link MinecraftDestination#isMobile()})
 * @param detail the hover detail (see {@link MinecraftDestination#detail()})
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public record SimpleMinecraftDestination<W, V>(
    Destination<WorldRegion<W, V>> destination,
    Component displayName,
    List<String> permissions,
    boolean mobile,
    Optional<Component> detail)
    implements MinecraftDestination<W, V> {

  /** Canonical constructor; defensively copies the permission list. */
  public SimpleMinecraftDestination {
    permissions = List.copyOf(permissions);
  }

  /**
   * Creates a destination without hover detail.
   *
   * @param destination the goal
   * @param displayName the name
   * @param permissions the required permissions
   * @param mobile whether the destination can move
   */
  public SimpleMinecraftDestination(
      Destination<WorldRegion<W, V>> destination,
      Component displayName,
      List<String> permissions,
      boolean mobile) {
    this(destination, displayName, permissions, mobile, Optional.empty());
  }

  /**
   * Creates a stationary destination without hover detail.
   *
   * @param destination the goal
   * @param displayName the name
   * @param permissions the required permissions
   */
  public SimpleMinecraftDestination(
      Destination<WorldRegion<W, V>> destination, Component displayName, List<String> permissions) {
    this(destination, displayName, permissions, false);
  }

  /**
   * Returns a copy of this destination with the given hover detail.
   *
   * @param detail the detail
   * @return the copy
   */
  public SimpleMinecraftDestination<W, V> withDetail(Component detail) {
    return new SimpleMinecraftDestination<>(
        destination, displayName, permissions, mobile, Optional.of(detail));
  }

  @Override
  public boolean isMobile() {
    return mobile;
  }
}
