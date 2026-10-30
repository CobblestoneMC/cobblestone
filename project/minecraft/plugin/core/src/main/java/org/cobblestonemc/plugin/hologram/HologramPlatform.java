/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * What {@link TrailLabelManager} needs from the platform. Called on the player's own thread.
 *
 * @param <A> the trip-agent type wrapping a player
 * @param <L> the native location type
 */
public interface HologramPlatform<A, L> {

  /**
   * Returns the coordinates of a location.
   *
   * @param location the location, in the player's world
   * @return its coordinates
   */
  Vec3 point(L location);

  /**
   * Returns where the player is, if they are online.
   *
   * @param agent the player
   * @return their position
   */
  Optional<PlayerPosition> position(A agent);

  /**
   * Returns a reader of the blocks in the player's world for this tick, which never loads a chunk.
   *
   * @param agent the player
   * @return the probe
   */
  TrailSmoother.BlockProbe probe(A agent);

  /**
   * Places one line of label text in the player's world, shown only to them. It is never hit:
   * {@link TrailLabelManager#swing} says what a swing lands on.
   *
   * @param agent the player
   * @param at the center of its text
   * @param text the text
   * @return the label, or empty if it can't be placed there from this thread
   */
  Optional<Hologram> spawn(A agent, Vec3 at, Component text);

  /**
   * Whether the player's swings land where they look, so they can hit a label's {@code Cancel}. A
   * Bedrock player on a touch screen hits wherever they tap, which the server never hears of, so
   * their labels go without one.
   *
   * @param agent the player
   * @return {@code true} if their labels should offer {@code Cancel}
   */
  default boolean aims(A agent) {
    return true;
  }
}
