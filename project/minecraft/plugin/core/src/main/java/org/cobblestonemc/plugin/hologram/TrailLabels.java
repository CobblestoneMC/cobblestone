/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.plugin.api.Navigator;

/**
 * Where a trail navigator floats its trip's label: the trip's name, a few blocks ahead of the
 * player over the trail, shown while they stand still; hitting {@code Cancel} above it cancels the
 * trip. A navigator attaches while its trip runs and says where its trail is; the labels are
 * placed, named and stacked against the player's other trips here.
 *
 * @param <L> the native location type
 */
public interface TrailLabels<L> {

  /**
   * Labels nothing.
   *
   * @param <L> the native location type
   * @return labels that are never shown
   */
  static <L> TrailLabels<L> none() {
    return new TrailLabels<>() {
      @Override
      public void attach(UUID player, Navigator<L> navigator, Source<L> source) {}

      @Override
      public void detach(UUID player, Navigator<L> navigator) {}
    };
  }

  /**
   * Starts labelling the trip {@code navigator} drives. Call once the trip has started.
   *
   * @param player the guided player
   * @param navigator the trip's navigator
   * @param source where its trail runs
   */
  void attach(UUID player, Navigator<L> navigator, Source<L> source);

  /**
   * Stops labelling the trip {@code navigator} drives and removes its label. Safe from any thread.
   *
   * @param player the guided player
   * @param navigator the trip's navigator
   */
  void detach(UUID player, Navigator<L> navigator);

  /**
   * Where a navigator's trail runs ahead of the player.
   *
   * @param <L> the native location type
   */
  @FunctionalInterface
  interface Source<L> {

    /**
     * Returns the first point on the trail the player is following (or a guide back to it) that is
     * {@code distance} blocks from them in a straight line, or empty for no label right now.
     *
     * @param distance how far from the player, in blocks
     * @return the point, in the player's world
     */
    Optional<L> labelPoint(double distance);
  }
}
