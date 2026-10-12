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
   * Returns where the player is looking, if they are online.
   *
   * @param agent the player
   * @return their gaze
   */
  Optional<Gaze> gaze(A agent);

  /**
   * Returns a reader of the blocks in the player's world for this tick, which never loads a chunk.
   *
   * @param agent the player
   * @return the probe
   */
  TrailSmoother.BlockProbe probe(A agent);

  /**
   * Returns how lit a point in the player's world looks: the brighter of its block light and its
   * sky light as dimmed by the time of day, from 0 to 15.
   *
   * @param agent the player
   * @param at the point
   * @return its light level, or 0 if it can't be read from this thread
   */
  int light(A agent, Vec3 at);

  /**
   * Places a label in the player's world, shown only to them.
   *
   * @param agent the player
   * @param at the center of its text
   * @param text the text
   * @param onHit run when the player hits the label
   * @return the label, or empty if it can't be placed there from this thread
   */
  Optional<Hologram> spawn(A agent, Vec3 at, Component text, Runnable onHit);
}
