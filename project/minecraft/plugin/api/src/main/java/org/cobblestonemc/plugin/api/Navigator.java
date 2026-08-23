/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Optional;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;

public interface Navigator<L> {

  /** Called once when the trip begins. */
  void start();

  /** Called on a schedule to render and advance the display. */
  void tick();

  /**
   * Hot-swaps the path being followed — used by live trips after a re-search.
   *
   * @param newPath the replacement path
   */
  void update(Path<L, MinecraftStepPayload> newPath);

  /**
   * Called once when the trip ends (completion, cancellation, or logout); releases any display
   * state.
   */
  void stop();

  /**
   * Returns whether the destination has been reached.
   *
   * @return {@code true} if complete
   */
  boolean isComplete();

  /**
   * Returns the estimated remaining traversal time, in seconds, from the player's current progress
   * along the path. Used for the live duration readout in {@code /cobblestone trips}.
   *
   * @return the remaining time in seconds
   */
  double remainingSeconds();

  /**
   * Returns and clears a request from the navigator to recalculate the route — e.g. the player has
   * strayed off the trail. The trip runs a fresh search when this returns {@code true}. Defaults to
   * never requesting.
   *
   * @return {@code true} if a recalculation is wanted (and is thereby consumed)
   */
  default boolean consumeRecalcRequest() {
    return false;
  }

  /**
   * Returns and clears a request for a short-range "guide" path from the player to the given target
   * location (the current step) — used to draw a real path back to the trail instead of a straight
   * line when the player drifts off. Defaults to never requesting.
   *
   * @return the target to guide toward, if one is requested
   */
  default Optional<L> consumeGuideRequest() {
    return Optional.empty();
  }

  /**
   * Supplies the result of a guide request for the navigator to render. Defaults to ignoring it.
   *
   * @param guide the short path from the player toward the current step
   */
  default void setGuidePath(Path<L, MinecraftStepPayload> guide) {
    // Navigators that don't use guide paths ignore this.
  }
}
