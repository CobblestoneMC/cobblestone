/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.List;

/**
 * The outcome of a single Tier-2 A* solve: a solved step sequence with its true cost, the target
 * being genuinely unreachable (the frontier emptied), or the solve giving up because it hit the
 * cell-visit limit. The last two are distinguished so the search can report {@code LIMIT_EXCEEDED}
 * (raise the limit) separately from {@code NO_ROUTE} (genuinely disconnected).
 *
 * @param <T> the payload type
 * @param <D> the domain type
 * @param outcome how the solve ended
 * @param steps the solved steps (empty unless {@link Outcome#SOLVED})
 * @param cost the true cost (meaningful only when solved)
 */
record Tier2Result<T, D extends Domain>(Outcome outcome, List<RawStep<T, D>> steps, double cost) {

  /** How a Tier-2 solve ended. */
  enum Outcome {
    /** A path to the target was found. */
    SOLVED,
    /** The frontier emptied without reaching the target — no path in this domain. */
    UNREACHABLE,
    /** The solve visited more cells than allowed and gave up (a memory guard, not a verdict). */
    LIMIT_EXCEEDED
  }

  boolean solved() {
    return outcome == Outcome.SOLVED;
  }

  static <T, D extends Domain> Tier2Result<T, D> solved(List<RawStep<T, D>> steps, double cost) {
    return new Tier2Result<>(Outcome.SOLVED, List.copyOf(steps), cost);
  }

  static <T, D extends Domain> Tier2Result<T, D> unreachable() {
    return new Tier2Result<>(Outcome.UNREACHABLE, List.of(), Double.POSITIVE_INFINITY);
  }

  static <T, D extends Domain> Tier2Result<T, D> limitExceeded() {
    return new Tier2Result<>(Outcome.LIMIT_EXCEEDED, List.of(), Double.POSITIVE_INFINITY);
  }
}
