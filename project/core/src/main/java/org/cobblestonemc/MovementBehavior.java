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
   * Returns the traversal state a search starts in.
   *
   * @return the starting state
   */
  default TraversalState initialState() {
    return TraversalState.DEFAULT;
  }
}
