/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.ArrayDeque;

/**
 * How fast a player is moving, from where they are each tick.
 *
 * <p>Movement reaches the server in packets that don't line up with its ticks: one tick can see no
 * movement and the next two ticks' worth, so a single tick's movement swings between nothing and
 * double. Speed is therefore how far the player moved over the last {@link #WINDOW_TICKS}, lightly
 * smoothed, which evens those swings out while still following a change of pace within about a
 * quarter of a second.
 */
final class SpeedMeter {

  /** How many ticks of movement speed is measured over. */
  static final int WINDOW_TICKS = 6;

  /** The share of the gap to the measured speed taken each tick: a time constant of two ticks. */
  private static final double SMOOTHING = 1 - Math.exp(-1 / 2.0);

  /** A move further than this in one tick is a teleport, not travel, in blocks. */
  private static final double MAX_TRAVEL_PER_TICK = 4.0;

  private static final double TICKS_PER_SECOND = 20.0;

  // how far the player moved in each of the last WINDOW_TICKS ticks, oldest first
  private final ArrayDeque<Double> travel = new ArrayDeque<>();
  private double travelTotal;
  private Vec3 last;
  private double speed;

  /**
   * Records where the player is this tick.
   *
   * @param position their position
   */
  void record(Vec3 position) {
    Vec3 previous = last;
    last = position;
    if (previous == null) {
      return;
    }
    double travelled = position.minus(previous).length();
    if (travelled > MAX_TRAVEL_PER_TICK) {
      travel.clear(); // a teleport, not travel: start measuring again from here
      travelTotal = 0;
      return;
    }
    travel.addLast(travelled);
    travelTotal += travelled;
    if (travel.size() > WINDOW_TICKS) {
      travelTotal -= travel.removeFirst();
    }
    double measured = travelTotal / travel.size() * TICKS_PER_SECOND;
    speed += SMOOTHING * (measured - speed);
  }

  /**
   * Returns how fast the player is moving.
   *
   * @return their speed, in blocks per second
   */
  double speed() {
    return speed;
  }
}
