/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;

/**
 * A named, permission-gated navigation target — e.g. a location, an Essentials home, or a town's
 * spawn.
 *
 * <p>The underlying {@link Destination} is the algorithm-level goal (a collection of {@code
 * DomainRegion}s); everything else here is presentation and access control the plugin layer cares
 * about.
 *
 * @param <W> The world type
 * @param <V> The 3D vector type
 */
public interface MinecraftDestination<W, V> {

  /**
   * Returns the core navigation goal for this destination.
   *
   * @return the destination
   */
  Destination<WorldRegion<W, V>> destination();

  /**
   * Returns the human-facing name, as Adventure rich text (may carry color/formatting).
   *
   * @return the display name
   */
  Component displayName();

  /**
   * Returns the permission nodes a player must hold — <b>all</b> of them — to use this destination.
   * An empty list means unrestricted.
   *
   * @return the required permissions
   */
  List<String> permissions();

  /**
   * Returns whether this destination can move (e.g. another player), so a trip to it should default
   * to live (auto-recalculating). Stationary destinations (locations, worlds) return {@code false}.
   *
   * @return {@code true} if the destination is mobile
   */
  default boolean isMobile() {
    return false;
  }

  /**
   * Returns the options this destination takes after its key, in the order they are typed — e.g.
   * {@code <x> <y> <z> [world]}. Required options come first. Empty (the default) for a destination
   * that is complete as it stands.
   *
   * <p>A destination with options is a template: its {@link #destination()} is not searched
   * directly. The command parses the typed tokens and searches whatever {@link
   * #configure(OptionValues)} returns.
   *
   * @return the options
   */
  default List<DestinationOption<?>> options() {
    return List.of();
  }

  /**
   * Returns this destination with its options applied — e.g. the coordinates template configured to
   * point at {@code 100 64 -200}. Called once per command, with a value for every required option
   * and for each optional one that was typed. The default, for a destination without options,
   * returns itself.
   *
   * @param values the parsed option values
   * @return the configured destination
   */
  default MinecraftDestination<W, V> configure(OptionValues values) {
    return this;
  }

  /**
   * Returns extra detail to show when the player hovers over this destination in command feedback
   * ("Searching for a route…", and the failure that may follow) — beneath its address, which is
   * always shown. Meant for destinations at one specific place, whose coordinates the player can
   * use to get there by hand even if no route is found (most of all {@code death}, whose location
   * they may have no other way to learn). Empty (the default) for anything else.
   *
   * @return the detail, if any
   */
  default Optional<Component> detail() {
    return Optional.empty();
  }
}
