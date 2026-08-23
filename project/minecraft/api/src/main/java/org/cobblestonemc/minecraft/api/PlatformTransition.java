/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

import org.cobblestonemc.api.TraversalState;

public interface PlatformTransition<R, P> {

  R origin();

  P destination();

  double cost();

  /**
   * The real traversal time in seconds (player-facing). Defaults to {@link #cost()} so existing
   * providers need no change until they distinguish danger/penalty weighting from actual duration.
   *
   * @return the traversal time
   */
  default double time() {
    return cost();
  }

  MinecraftStepPayload payload();

  default TraversalState apply(TraversalState in) {
    return in;
  }
}
