/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.Collection;
import org.cobblestonemc.api.TraversalState;

/**
 * How an agent moves: the single arbiter of which cells it can reach in one step from a given cell,
 * at what cost, and as what kind of step.
 *
 * <p>One behavior answers for every way the agent can move (walking, swimming, mining, flying …),
 * so it can look at each neighbor once and decide how — or whether — the agent gets there, rather
 * than several independent producers each re-examining the same blocks. The kind of each step
 * travels in the movement's payload, typed by the platform.
 *
 * <p>Block lookups (in Minecraft) go through a chunk provider and surface as a {@link FutureOr}, so
 * {@code movements} returns a {@code FutureOr} of the movement set; a behavior with no I/O returns
 * an immediate value. Ability/permission gating is the behavior's own concern, typically settled
 * once when it is built for a search.
 *
 * @param <A> the agent type
 * @param <T> the payload type
 * @param <D> the domain type
 */
@FunctionalInterface
public interface MovementBehavior<A extends Agent, T, D extends Domain> {

  /**
   * Produces every movement reachable from {@code from} in a single step.
   *
   * @param agent the navigating agent (for capability/context)
   * @param from the starting cell
   * @param domain the domain being traversed (same for every produced movement)
   * @param state the current traversal state
   * @param goal the cell of the leg's target nearest {@code from}
   * @return the reachable movements, possibly pending on block I/O
   */
  FutureOr<Collection<Movement<T>>> movements(
      A agent, Cell from, D domain, TraversalState state, Cell goal);
}
