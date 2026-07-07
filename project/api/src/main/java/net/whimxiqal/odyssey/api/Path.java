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
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
public interface Path<T extends Enum<T>, I, D extends Domain> {

  /**
   * Returns the ordered steps of this path, origin first.
   *
   * @return the steps (non-empty)
   */
  List<Step<T, I, D>> steps();

  /**
   * Returns the total cost of the path in seconds.
   *
   * @return the total cost
   */
  double cost();

  /**
   * Returns the first step (the origin).
   *
   * @return the first step
   */
  default Step<T, I, D> first() {
    return steps().get(0);
  }

  /**
   * Returns the last step (the destination).
   *
   * @return the last step
   */
  default Step<T, I, D> last() {
    List<Step<T, I, D>> steps = steps();
    return steps.get(steps.size() - 1);
  }
}
