/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.List;

/**
 * The outcome of a single Tier-2 A* solve: either a solved step sequence with its true cost, or an
 * indication that the target could not be reached (no path, or a limit was hit).
 *
 * @param <T> the payload type
 * @param <D> the domain type
 * @param solved whether a path was found
 * @param steps the solved steps (empty if not solved)
 * @param cost the true cost (meaningful only when {@code solved})
 */
record Tier2Result<T, D extends Domain>(
    boolean solved, List<RawStep<T, D>> steps, double cost) {

  static <T, D extends Domain> Tier2Result<T, D> solved(
      List<RawStep<T, D>> steps, double cost) {
    return new Tier2Result<>(true, List.copyOf(steps), cost);
  }

  static <T, D extends Domain> Tier2Result<T, D> unreachable() {
    return new Tier2Result<>(false, List.of(), Double.POSITIVE_INFINITY);
  }
}
