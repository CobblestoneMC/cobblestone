/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * Where the search reads the clock, and where it asks to be woken later.
 *
 * <p>In production this is the system clock and the scheduler's delayed execution, and nothing
 * about the search changes. The seam exists for measurement: a benchmark that has to wait in real
 * time for every simulated disk read takes hours instead of seconds, and — worse — its results vary
 * with whatever else the machine was doing.
 *
 * <p>A harness supplies a <b>virtual</b> clock instead. A simulated chunk read completes
 * immediately but advances that clock by the delay it was supposed to take, so the search sees
 * spinning-disk-realistic timing — park counts, prefetch effectiveness, deadline pressure — at full
 * speed, and reproducibly. Both halves are needed: reading the clock alone would let a search run
 * past a deadline that never arrives, and scheduling alone would leave elapsed time measured
 * against a wall clock the simulation is not following.
 *
 * <p>Implementations must be safe to call from any thread.
 */
public interface TimeSource {

  /**
   * Returns the current time in milliseconds, on whatever timeline this source defines.
   *
   * <p>Only differences between two readings are meaningful. A virtual clock is under no obligation
   * to resemble wall-clock time, or to have started at the epoch.
   *
   * @return the current time in milliseconds
   */
  long millis();

  /**
   * Runs {@code task} once, after {@code delayMillis} have passed on this source's timeline.
   *
   * @param task the task
   * @param delayMillis how long to wait, in milliseconds on this timeline
   */
  void schedule(Runnable task, long delayMillis);

  /**
   * Returns the system clock, scheduling onto the given scheduler.
   *
   * @param scheduler the scheduler to run delayed tasks on
   * @return the system time source
   */
  static TimeSource system(Scheduler scheduler) {
    return new TimeSource() {
      @Override
      public long millis() {
        return System.currentTimeMillis();
      }

      @Override
      public void schedule(Runnable task, long delayMillis) {
        scheduler.runAsyncLater(task, delayMillis);
      }
    };
  }
}
