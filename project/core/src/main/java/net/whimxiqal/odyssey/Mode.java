/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

import java.util.Collection;

/**
 * A method of transportation (walk, swim, fly, mine, fall, boat, horse …).
 *
 * <p>Given a starting cell, the domain, the agent context, and the current {@link TraversalState},
 * a mode yields the neighbor cells it can reach in one step as {@link Movement}s. Block lookups
 * (in Minecraft) go through a chunk provider and surface as a {@link FutureOr}, so {@code step}
 * returns a {@code FutureOr} of the movement set; pure modes with no I/O return an immediate value.
 *
 * <p>Ability/permission gating happens when the mode <i>list</i> is assembled for a search (e.g. a
 * fly mode is only included when the agent can fly), never inside {@code step}.
 *
 * @param <A> the agent type
 * @param <T> the payload type
 * @param <D> the domain type
 */
public interface Mode<A extends Agent, T, D extends Domain> {

  /**
   * Produces every cell reachable from {@code from} in a single step of this mode.
   *
   * @param agent the navigating agent (for capability/context)
   * @param from the starting cell
   * @param domain the domain being traversed (same for every produced movement)
   * @param state the current traversal state
   * @return the reachable movements, possibly pending on block I/O
   */
  FutureOr<Collection<Movement<T>>> step(A agent, Cell from, D domain, TraversalState state);
}
