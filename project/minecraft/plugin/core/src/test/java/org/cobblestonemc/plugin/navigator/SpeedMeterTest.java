/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** How {@link SpeedMeter} measures a player's speed. */
class SpeedMeterTest {

  @Test
  void measuresASteadyPace() {
    SpeedMeter meter = new SpeedMeter();
    for (int tick = 0; tick < 40; tick++) {
      meter.record(new Vec3(tick * 0.28, 0, 0)); // 5.6 blocks a second
    }
    assertEquals(5.6, meter.speed(), 0.05);
  }

  @Test
  void holdsSteadyWhenMovementArrivesInBursts() {
    // Sprinting, but the server sees the moves bunched: nothing one tick, two ticks' worth the
    // next.
    SpeedMeter meter = new SpeedMeter();
    double x = 0;
    double slowest = Double.POSITIVE_INFINITY;
    double fastest = 0;
    for (int tick = 0; tick < 80; tick++) {
      if (tick % 2 == 1) {
        x += 2 * 0.28; // 5.6 blocks a second on average
      }
      meter.record(new Vec3(x, 0, 0));
      if (tick >= 40) {
        slowest = Math.min(slowest, meter.speed());
        fastest = Math.max(fastest, meter.speed());
      }
    }
    assertEquals(5.6, (slowest + fastest) / 2, 0.2);
    assertTrue(fastest - slowest < 0.3, "the speed swings from " + slowest + " to " + fastest);
  }

  @Test
  void followsAStopQuickly() {
    SpeedMeter meter = new SpeedMeter();
    for (int tick = 0; tick < 40; tick++) {
      meter.record(new Vec3(tick * 0.28, 0, 0));
    }
    for (int tick = 0; tick < 10; tick++) {
      meter.record(new Vec3(39 * 0.28, 0, 0));
    }
    assertTrue(meter.speed() < 0.5, "half a second after stopping: " + meter.speed());
  }

  @Test
  void aTeleportIsNotTravel() {
    SpeedMeter meter = new SpeedMeter();
    meter.record(new Vec3(0, 0, 0));
    meter.record(new Vec3(500, 0, 0));
    meter.record(new Vec3(500, 0, 0));
    assertEquals(0, meter.speed(), 1e-9);
  }
}
