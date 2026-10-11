/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.Validity;

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
 * @param validity whether it can be navigated to (see {@link MinecraftDestination#validity()})
 * @param existence whether its target still exists (see {@link MinecraftDestination#exists()})
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public record SimpleMinecraftDestination<W, V>(
    Destination<WorldRegion<W, V>> destination,
    Component displayName,
    List<String> permissions,
    boolean mobile,
    Optional<Component> detail,
    Validity validity,
    BooleanSupplier existence)
    implements MinecraftDestination<W, V> {

  /** Canonical constructor; defensively copies the permission list. */
  public SimpleMinecraftDestination {
    permissions = List.copyOf(permissions);
  }

  /**
   * Creates a valid destination without hover detail, whose target always exists.
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
    this(
        destination,
        displayName,
        permissions,
        mobile,
        Optional.empty(),
        Validity.valid(),
        () -> true);
  }

  /**
   * Creates a valid, stationary destination without hover detail.
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
   * A destination that cannot be navigated to right now, for the given reason — e.g. {@code death}
   * for a player who has not died.
   *
   * @param displayName the name
   * @param permissions the required permissions
   * @param reason the message shown to the player
   * @param <W> the platform world type
   * @param <V> the platform vector type
   * @return the destination
   */
  public static <W, V> SimpleMinecraftDestination<W, V> invalid(
      Component displayName, List<String> permissions, Component reason) {
    return new SimpleMinecraftDestination<W, V>(List::of, displayName, permissions)
        .withValidity(Validity.invalid(reason));
  }

  /**
   * Returns a copy of this destination with the given hover detail.
   *
   * @param detail the detail
   * @return the copy
   */
  public SimpleMinecraftDestination<W, V> withDetail(Component detail) {
    return new SimpleMinecraftDestination<>(
        destination, displayName, permissions, mobile, Optional.of(detail), validity, existence);
  }

  /**
   * Returns a copy of this destination with the given validity.
   *
   * @param validity the validity
   * @return the copy
   */
  public SimpleMinecraftDestination<W, V> withValidity(Validity validity) {
    return new SimpleMinecraftDestination<>(
        destination, displayName, permissions, mobile, detail, validity, existence);
  }

  /**
   * Returns a copy of this destination whose target exists only while {@code existence} says so.
   *
   * @param existence polled during a trip; see {@link MinecraftDestination#exists()}
   * @return the copy
   */
  public SimpleMinecraftDestination<W, V> withExistence(BooleanSupplier existence) {
    return new SimpleMinecraftDestination<>(
        destination, displayName, permissions, mobile, detail, validity, existence);
  }

  @Override
  public boolean isMobile() {
    return mobile;
  }

  @Override
  public boolean exists() {
    return existence.getAsBoolean();
  }
}
