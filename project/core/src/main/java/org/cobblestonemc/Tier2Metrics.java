/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * The bookkeeping one {@link Tier2Search} keeps purely to explain itself afterwards — nothing here
 * is read by the algorithm, which is why it lives out here rather than among the search's state.
 *
 * <p>Two numbers carry most of the diagnostic weight. <b>Parking</b> is what a search does while a
 * mode waits on a block it does not have: wall time is essentially active + parked, so the split
 * says whether a slow search is thinking too hard or waiting too long, and the mean park says how
 * long each wait costs. <b>Approach</b> is how near the target the search actually got against how
 * far it started, which separates the two ways a solve runs out of time — a search grinding along a
 * route it is following closes the gap, while one walled in (by terrain, or by chunks the load
 * policy will not materialize) burns its whole budget without the gap moving.
 *
 * <p>Not thread-safe: every method is called from inside the search's single-flight pump.
 */
final class Tier2Metrics {

  private final Stopwatch active = new Stopwatch();
  private final double startDistance;

  private long parkedSince = System.currentTimeMillis();
  private boolean everPumped;
  private long parks;
  private long parkedMillis;

  private double closestApproach = Double.POSITIVE_INFINITY;
  private int expanded;

  Tier2Metrics(double startDistance) {
    this.startDistance = startDistance;
  }

  /**
   * Records the end of a park, returning how long it lasted in milliseconds.
   *
   * <p>Call once per pump run, not once per pass through it: a signal that races into a pump
   * already running did not wait on anything, and counting it would drag the mean park towards zero
   * with exactly the wake-ups that mean the search was not waiting. The gap before the very first
   * pump is scheduling latency rather than a block wait, so it is measured but not counted.
   *
   * @return the length of the park just ended, in milliseconds
   */
  long woke() {
    long parked = System.currentTimeMillis() - parkedSince;
    if (everPumped) {
      parks++;
      parkedMillis += parked;
    }
    everPumped = true;
    return parked;
  }

  /** Marks the search as running (the active clock ticks until {@link #pause()}). */
  void resume() {
    active.resume();
  }

  /** Marks the search as idle. */
  void pause() {
    active.pause();
  }

  /** Stamps the start of a park, i.e. the moment the pump releases. */
  void park() {
    parkedSince = System.currentTimeMillis();
  }

  /** Counts one node expansion. */
  void expanded() {
    expanded++;
  }

  /** Records how near the target a newly closed node is, in blocks. */
  void reached(double distanceToTarget) {
    closestApproach = Math.min(closestApproach, distanceToTarget);
  }

  /**
   * Renders the one-line summary the search logs when it finishes.
   *
   * @param visited how many cell-states the search is holding
   * @return the summary
   */
  String format(int visited) {
    double remaining = Math.min(closestApproach, startDistance);
    return ("activeTime:%dms, parkedTime:%dms, parks:%d (mean %.1fms), visited:%d, expanded:%d, "
            + "approach:%.0f/%.0f blocks (%.0f%%)")
        .formatted(
            active.elapsed(),
            parkedMillis,
            parks,
            parks == 0 ? 0.0 : (double) parkedMillis / parks,
            visited,
            expanded,
            startDistance - remaining,
            startDistance,
            startDistance <= 0 ? 100.0 : (1 - remaining / startDistance) * 100);
  }
}
