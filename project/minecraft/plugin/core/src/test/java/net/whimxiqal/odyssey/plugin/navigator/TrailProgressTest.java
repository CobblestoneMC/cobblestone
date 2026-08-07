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

/** Segment advancement, corner-cutting tolerance, and completion for {@link TrailProgress}. */
class TrailProgressTest {

  // An L-shaped path: east 10, then south 10.
  private static final List<Vec3> PATH =
      List.of(new Vec3(0, 0, 0), new Vec3(10, 0, 0), new Vec3(10, 0, 10));

  @Test
  void staysOnSegmentWhilePartwayThrough() {
    assertEquals(0, TrailProgress.advance(PATH, 0, new Vec3(5, 0, 0)));
  }

  @Test
  void advancesWhenProjectionPassesSegmentEnd() {
    // At the corner, segment 0 is complete but the player is only at the start of segment 1.
    assertEquals(1, TrailProgress.advance(PATH, 0, new Vec3(10, 0, 0)));
  }

  @Test
  void toleratesCuttingTheCorner() {
    // Player overshoots segment 0 (x=12) — still advances, not skips past the corner incorrectly.
    assertEquals(1, TrailProgress.advance(PATH, 0, new Vec3(12, 0, 3)));
  }

  @Test
  void reachesEndWhenPastFinalPoint() {
    assertEquals(2, TrailProgress.advance(PATH, 0, new Vec3(10, 0, 12)));
  }

  @Test
  void neverRewindsBelowForemost() {
    // Even standing back at the origin, an already-advanced foremost does not move backward.
    assertEquals(1, TrailProgress.advance(PATH, 1, new Vec3(0, 0, 0)));
  }

  @Test
  void skipsZeroLengthSegments() {
    List<Vec3> withDuplicate =
        List.of(new Vec3(0, 0, 0), new Vec3(0, 0, 0), new Vec3(5, 0, 0));
    assertEquals(1, TrailProgress.advance(withDuplicate, 0, new Vec3(0, 0, 0)));
  }
}
