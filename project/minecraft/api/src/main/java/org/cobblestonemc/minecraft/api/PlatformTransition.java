/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

import org.cobblestonemc.api.TraversalState;

/**
 * A transition in a platform's own types: a jump (teleport, portal, mount, ...) from an entry
 * region to an arrival position. Platform APIs bind the generics (e.g. Paper's and Sponge's {@code
 * Transition}).
 *
 * @param <R> the origin region type
 * @param <P> the destination position type
 */
public interface PlatformTransition<R, P> {

  /**
   * Returns the entry area the agent must reach to use this transition.
   *
   * @return the origin region
   */
  R origin();

  /**
   * Returns the point the agent arrives at after traversing this transition.
   *
   * @return the destination position
   */
  P destination();

  /**
   * Returns the algorithm traversal cost in seconds (what the search minimizes).
   *
   * @return the cost
   */
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

  /**
   * Returns the payload carried through to the resulting steps (e.g. a command to run).
   *
   * @return the payload
   */
  MinecraftStepPayload payload();

  /**
   * Transforms the incoming traversal state on traversal. The default is the identity (a plain
   * teleport changes nothing); a horse-mount transition sets the vehicle state, for example.
   *
   * @param in the state before traversal
   * @return the state after traversal
   */
  default TraversalState apply(TraversalState in) {
    return in;
  }
}
