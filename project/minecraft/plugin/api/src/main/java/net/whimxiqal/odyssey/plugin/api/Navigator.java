/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;

public interface Navigator<L> {

  /**
   * Called once when the trip begins.
   */
  void start();

  /**
   * Called on a schedule to render and advance the display.
   */
  void tick();

  /**
   * Hot-swaps the path being followed — used by live trips after a re-search.
   *
   * @param newPath the replacement path
   */
  void update(Path<Step<L, MinecraftStepPayload>> newPath);

  /**
   * Called once when the trip ends (completion, cancellation, or logout); releases any display state.
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
   * along the path. Used for the live duration readout in {@code /odyssey trips}.
   *
   * @return the remaining time in seconds
   */
  double remainingSeconds();
}
