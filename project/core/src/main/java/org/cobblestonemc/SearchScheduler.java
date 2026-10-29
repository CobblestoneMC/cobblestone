/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.concurrent.Executor;

/**
 * Where a search runs: the executor its steps and solves run on, and the clock it reads and is
 * woken on.
 *
 * <p>A search never blocks a thread, so this is all it needs. In production both come from {@link
 * Workers}: its compute threads, and the system clock with its timer. Tests run search work inline,
 * and the benchmark on a virtual clock, by supplying their own.
 */
public interface SearchScheduler {

  /**
   * Returns the executor search work runs on. "Async" throughout Cobblestone means "on this".
   *
   * @return the executor
   */
  Executor executor();

  /**
   * Returns the clock the search reads, and schedules its wake-ups on.
   *
   * <p>A delayed task runs on {@link #executor()}. A benchmark harness supplies a virtual clock so
   * that simulated IO delays cost no real time and a run is reproducible; see {@link TimeSource}.
   *
   * @return the time source
   */
  TimeSource time();
}
