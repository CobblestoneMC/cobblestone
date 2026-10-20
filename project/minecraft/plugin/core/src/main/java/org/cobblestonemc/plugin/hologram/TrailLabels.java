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
 * Where a trail navigator floats its trip's labels. One names the trip over the trail head, shown
 * while the player stands still; hitting {@code Cancel} below it cancels the trip. The other names
 * the destination over it, once the player is close. A navigator attaches while its trip runs and
 * says where its trail is; the labels are placed, named and stacked against the player's other
 * trips here.
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
  interface Source<L> {

    /**
     * Returns the trail head, where the trail the player is following (or the guide back to it)
     * starts ahead of them, or empty for no label there right now.
     *
     * @return the point, in the player's world
     */
    Optional<L> headPoint();

    /**
     * Returns where the route ends, or empty if not in the player's world or labels are off.
     *
     * @return the point, in the player's world
     */
    Optional<L> goalPoint();
  }
}
