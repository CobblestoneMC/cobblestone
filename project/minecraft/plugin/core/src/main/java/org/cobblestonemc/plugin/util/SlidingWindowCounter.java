/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.util;

import java.time.Duration;
import java.util.ArrayDeque;

/**
 * A thread-safe count of events over a trailing time window. Each {@link #record()} enqueues the
 * current time; {@link #count()} returns how many events fall within the window, evicting older
 * ones. Because reporting the count over a one-hour window <em>is</em> the per-hour rate, no
 * division (and so no rounding a small rate down to zero) is needed for an integer metric.
 *
 * <p>Uses {@link System#nanoTime()} so the window is immune to wall-clock adjustments.
 */
public final class SlidingWindowCounter {

  private final long windowNanos;
  private final ArrayDeque<Long> timestamps = new ArrayDeque<>();

  public SlidingWindowCounter(Duration window) {
    this.windowNanos = window.toNanos();
  }

  /** Records one event at the current time and evicts any that have aged out. */
  public synchronized void record() {
    long now = System.nanoTime();
    timestamps.addLast(now);
    evict(now);
  }

  /** The number of events recorded within the trailing window. */
  public synchronized int count() {
    evict(System.nanoTime());
    return timestamps.size();
  }

  private void evict(long now) {
    long cutoff = now - windowNanos;
    Long head;
    // Subtraction comparison is safe across nanoTime's arbitrary (possibly negative) origin.
    while ((head = timestamps.peekFirst()) != null && head - cutoff < 0) {
      timestamps.removeFirst();
    }
  }
}
