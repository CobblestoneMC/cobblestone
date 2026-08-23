/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

import java.util.List;

/**
 * The flattened end-to-end result of a successful search: an ordered list of {@link Step}s from
 * origin to destination.
 *
 * <p>Steps may cross domain instances (all of the same domain <i>type</i> {@code D}); a domain
 * change or an instruction-bearing step marks a transition point.
 *
 * <p>{@link #cost()} and {@link #duration()} are derived from the steps rather than stored, so they
 * can never drift from the step list. A caller that reads them in a hot loop should cache the
 * result.
 *
 * @param <P> the position type
 * @param <T> the payload type
 */
public record Path<P, T>(P origin, List<Step<P, T>> steps) {

  public Path {
    steps = List.copyOf(steps);
  }

  /**
   * Returns the total algorithm cost of the path in seconds, summed from its steps.
   *
   * @return the total cost
   */
  public double cost() {
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
  public double duration() {
    double total = 0.0;
    for (Step<P, T> step : steps()) {
      total += step.time();
    }
    return total;
  }
}
