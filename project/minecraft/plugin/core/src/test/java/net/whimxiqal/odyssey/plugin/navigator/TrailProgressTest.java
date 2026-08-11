/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Step advancement, corner-cutting tolerance, and completion for {@link TrailProgress}. */
class TrailProgressTest {

  // Start at the origin, then an L-shaped path: east 10 (step 0), then south 10 (step 1).
  private static final Vec3 ORIGIN = new Vec3(0, 0, 0);
  private static final List<Vec3> PATH = List.of(new Vec3(10, 0, 0), new Vec3(10, 0, 10));

  @Test
  void staysOnStepWhilePartwayThrough() {
    // Halfway to the first destination — step 0 is not yet complete.
    assertEquals(0, TrailProgress.advance(PATH, ORIGIN, 0, new Vec3(5, 0, 0)));
  }

  @Test
  void doesNotCreditTheFirstStepFromTheOrigin() {
    // Standing at the origin, no step has been completed — the crux of the teleport-back bug.
    assertEquals(0, TrailProgress.advance(PATH, ORIGIN, 0, ORIGIN));
  }

  @Test
  void advancesWhenProjectionPassesTheDestination() {
    // At the corner, step 0 is complete but step 1 is only just beginning.
    assertEquals(1, TrailProgress.advance(PATH, ORIGIN, 0, new Vec3(10, 0, 0)));
  }

  @Test
  void toleratesCuttingTheCorner() {
    // Player overshoots step 0 (x=12) — still advances, not skipping past the corner incorrectly.
    assertEquals(1, TrailProgress.advance(PATH, ORIGIN, 0, new Vec3(12, 0, 3)));
  }

  @Test
  void reachesEndWhenPastFinalDestination() {
    // Every step complete: the index runs one past the last (points.size()).
    assertEquals(2, TrailProgress.advance(PATH, ORIGIN, 0, new Vec3(10, 0, 12)));
  }

  @Test
  void neverRewindsBelowForemost() {
    // Even standing back at the origin, an already-advanced foremost does not move backward.
    assertEquals(1, TrailProgress.advance(PATH, ORIGIN, 1, ORIGIN));
  }

  @Test
  void skipsZeroLengthSegments() {
    // A duplicate destination equal to the origin: step 0 is already passed.
    List<Vec3> withDuplicate = List.of(new Vec3(0, 0, 0), new Vec3(5, 0, 0));
    assertEquals(1, TrailProgress.advance(withDuplicate, ORIGIN, 0, ORIGIN));
  }
}
