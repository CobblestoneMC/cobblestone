/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.List;

/**
 * The outcome of a single Tier-2 A* solve: a solved step sequence with its true cost, the target
 * being genuinely unreachable (the frontier emptied), or the solve giving up because it hit the
 * cell-visit limit. The last two are distinguished so the search can report {@code LIMIT_EXCEEDED}
 * (raise the limit) separately from {@code NO_ROUTE} (genuinely disconnected).
 *
 * @param <T> the payload type
 * @param <D> the domain type
 */
sealed interface Tier2Result<T, D extends Domain> permits Tier2Result.Solved, Tier2Result.Failed {

  /** How a Tier-2 solve ended. */
  enum FailureOutcome {
    /** The frontier emptied without reaching the target — no path in this domain. */
    UNREACHABLE,
    /** The solve visited more cells than allowed and gave up (a memory guard, not a verdict). */
    LIMIT_EXCEEDED,
    TIMED_OUT
  }

  record Solved<T, D extends Domain>(List<RawStep<T, D>> steps, double cost)
      implements Tier2Result<T, D> {}

  record Failed<T, D extends Domain>(FailureOutcome outcome) implements Tier2Result<T, D> {}
}
