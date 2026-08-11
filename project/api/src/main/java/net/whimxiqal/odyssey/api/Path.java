/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import java.util.List;

/**
 * The flattened end-to-end result of a successful search: an ordered list of {@link Step}s from
 * origin to destination.
 *
 * <p>Steps may cross domain instances (all of the same domain <i>type</i> {@code D}); a domain
 * change or an instruction-bearing step marks a transition point.
 *
 * <p>{@link #cost()} and {@link #duration()} are derived from the steps rather than stored, so they
 * can never drift from the step list. A caller that reads them in a hot loop should cache the result.
 *
 * @param <P> the position type
 * @param <T> the payload type
 */
public interface Path<P, T> {

  P origin();

  /**
   * Returns the ordered steps of this path, origin first.
   *
   * @return the steps (non-empty)
   */
  List<Step<P, T>> steps();

  /**
   * Returns the total algorithm cost of the path in seconds, summed from its steps.
   *
   * @return the total cost
   */
  default double cost() {
    double total = 0.0;
    for (Step<P, T> step : steps()) {
      total += step.cost();
    }
    return total;
  }

  /**
   * Returns the total real traversal time of the path in seconds, summed from its steps.
   *
   * @return the total duration
   */
  default double duration() {
    double total = 0.0;
    for (Step<P, T> step : steps()) {
      total += step.time();
    }
    return total;
  }
}
