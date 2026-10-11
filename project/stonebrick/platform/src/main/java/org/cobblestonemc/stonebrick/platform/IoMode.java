/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

/**
 * How a simulated chunk read spends the time it is supposed to take.
 *
 * <p>All three are worth having, and they answer different questions.
 */
public enum IoMode {

  /**
   * No delay at all: a fetch completes on the calling thread.
   *
   * <p>Measures the search's own CPU cost with the least variance, which is what you want while
   * tuning a heuristic. It also makes a solve effectively synchronous, so it is the mode that
   * isolates algorithm changes from scheduling effects.
   */
  ZERO,

  /**
   * The modelled delay is a real wait on a real clock.
   *
   * <p>The highest fidelity and the worst benchmark: a run takes as long as the disk it is
   * pretending to be, and its numbers move with whatever else the machine is doing. Use it to
   * confirm a change that {@link #VIRTUAL} already showed to be good, not to find one.
   */
  SIMULATED,

  /**
   * The modelled delay passes on a virtual clock that advances only when everything is waiting.
   *
   * <p><b>The mode a benchmark should normally use.</b> A read completes after the right amount of
   * simulated time without anyone sleeping, so the search sees realistic park counts, prefetch
   * effectiveness and deadline pressure — the behaviour a slow disk actually causes — at the speed
   * of {@link #ZERO} and with the reproducibility of a pure function.
   */
  VIRTUAL
}
