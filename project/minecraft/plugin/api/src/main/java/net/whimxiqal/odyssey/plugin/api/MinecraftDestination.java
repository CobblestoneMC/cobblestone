/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.minecraft.api.WorldRegion;

/**
 * A named, permission-gated navigation target surfaced by a {@link DestinationProvider} — e.g. a
 * waypoint, an Essentials home, or a town's spawn.
 *
 * <p>The underlying {@link Destination} is the algorithm-level goal (a collection of
 * {@code DomainRegion}s); everything else here is presentation and access control the plugin layer
 * cares about.
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
}
