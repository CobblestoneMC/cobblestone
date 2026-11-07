/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.Collection;
import org.cobblestonemc.api.TraversalState;
import org.jetbrains.annotations.Nullable;

/**
 * Which cells can be reached in one step from a given cell, at what cost, and as what kind of step.
 *
 * @param <T> the payload type
 * @param <D> the domain type
 */
@FunctionalInterface
public interface MovementBehavior<T, D extends Domain> {

  /**
   * Produces every movement reachable from {@code from} in a single step.
   *
   * @param from the starting cell
   * @param domain the domain being traversed
   * @param state the current traversal state
   * @param goal the cell of the search's current target nearest {@code from}
   * @return the reachable movements, possibly pending on I/O
   */
  FutureOr<Collection<Movement<T>>> movements(Cell from, D domain, TraversalState state, Cell goal);

  /**
   * Produces every movement reachable from {@code from} in a single step, knowing which cell the
   * route to {@code from} arrived from. A behavior can price a step by how it follows the one
   * before, such as charging for a change of direction. By default the arrival is ignored.
   *
   * <p>The search passes the cell its cheapest route so far came from, and keeps the costs it is
   * given. A cost that depends on the arrival is therefore a nudge rather than exact: a cell
   * reached cheaply from one side is not searched again for a route arriving from another.
   *
   * @param from the starting cell
   * @param previous the cell the route to {@code from} came from, or {@code null} at the start
   * @param domain the domain being traversed
   * @param state the current traversal state
   * @param goal the cell of the search's current target nearest {@code from}
   * @return the reachable movements, possibly pending on I/O
   */
  default FutureOr<Collection<Movement<T>>> movements(
      Cell from, @Nullable Cell previous, D domain, TraversalState state, Cell goal) {
    return movements(from, domain, state, goal);
  }

  /**
   * Returns the traversal state a search starts in.
   *
   * @return the starting state
   */
  default TraversalState initialState() {
    return TraversalState.DEFAULT;
  }
}
