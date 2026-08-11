/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

/**
 * An "anti-mode": where a {@link Mode} <i>produces</i> reachable cells, a restriction
 * <i>removes</i> them. After the modes propose movements for a cell, the search discards any whose
 * destination a restriction marks impassable — used by integrations (region-protection plugins,
 * donor-only areas) to keep routes out of places the agent may not enter.
 *
 * <p>The verdict is a {@link FutureOr} so a check may resolve asynchronously (e.g. a protection
 * plugin doing a database lookup); an immediate answer never parks the search.
 *
 * @param <A> the agent type
 * @param <D> the domain type
 */
public interface Restriction<A extends Agent, D extends Domain> {

  /**
   * Returns whether the agent is barred from entering the given cell.
   *
   * @param agent the navigating agent
   * @param cell the candidate cell
   * @param domain the domain being traversed
   * @return {@code true} if the cell is impassable to this agent (drop movements into it)
   */
  FutureOr<Boolean> impassable(A agent, Cell cell, D domain);
}
