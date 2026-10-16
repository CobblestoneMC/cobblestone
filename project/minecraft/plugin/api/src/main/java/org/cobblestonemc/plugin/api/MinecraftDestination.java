/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.minecraft.api.WorldRegion;

/**
 * A named navigation target — e.g. a location, an Essentials home, or a town's spawn — as a {@link
 * DestinationLeaf} builds it once a player has picked it. Who may use it, and any options typed
 * after its key, belong to the leaf.
 *
 * <p>The underlying {@link Destination} is the algorithm-level goal (a collection of regions);
 * everything else here is presentation the plugin layer cares about.
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
   * Returns whether this destination can move (e.g. another player), so a trip to it should default
   * to live (auto-recalculating). Stationary destinations (locations, worlds) return {@code false}.
   *
   * @return {@code true} if the destination is mobile
   */
  default boolean isMobile() {
    return false;
  }

  /**
   * Returns whether this destination already shows its name in the world, as a player's name tag or
   * a named pet's name does. Cobblestone floats a label naming a destination over it once the
   * player gets close, unless it already has one. {@code false} by default.
   *
   * @return {@code true} if it shows its own name
   */
  default boolean hasLabel() {
    return false;
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

  /**
   * Returns whether this destination's target still exists, for a target that can stop existing — a
   * player who logs off, say. A trip polls it about once a second and ends quietly once it returns
   * {@code false}. Merely being out of reach (unloaded, say) is not ceasing to exist. Always {@code
   * true} by default.
   *
   * <p>Called on the thread that owns the travelling player, so it must be cheap.
   *
   * @return {@code false} once the target is gone for good
   */
  default boolean exists() {
    return true;
  }
}
