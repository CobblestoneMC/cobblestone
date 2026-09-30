/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * Accumulated elapsed time, measured against a supplied {@link TimeSource}.
 *
 * <p>Against the injected clock rather than the system one so that a search's reported active and
 * parked times follow the same timeline as its deadline. Under a virtual clock the two would
 * otherwise disagree wildly — a solve would report milliseconds of work against a simulated hour of
 * waiting — and the split between them is the most useful thing the metrics say.
 */
public class Stopwatch {

  private final TimeSource time;
  private long duration;
  private long startedAt;

  /**
   * Creates a stopwatch reading the given clock.
   *
   * @param time the clock
   */
  public Stopwatch(TimeSource time) {
    this.time = time;
    this.duration = 0;
    this.startedAt = 0;
  }

  /** Starts counting, if not already counting. */
  public void resume() {
    if (this.startedAt == 0) {
      this.startedAt = time.millis();
    }
  }

  /** Stops counting, if counting. */
  public void pause() {
    if (this.startedAt > 0) {
      this.duration += time.millis() - this.startedAt;
      this.startedAt = 0;
    }
  }

  /**
   * Returns the total time counted so far.
   *
   * @return the elapsed milliseconds
   */
  public long elapsed() {
    if (this.startedAt == 0) {
      return this.duration;
    }
    return this.duration + (time.millis() - this.startedAt);
  }
}
