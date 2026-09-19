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

/** Corner rounding, continuity, and sharp ends for {@link TrailCurve}. */
class TrailCurveTest {

  private static final double TOLERANCE = 1e-9;

  // An L-shaped turn: east from A to B, then south from B to C.
  private static final Vec3 A = new Vec3(0, 0, 0);
  private static final Vec3 B = new Vec3(1, 0, 0);
  private static final Vec3 C = new Vec3(1, 0, 1);

  @Test
  void straightWithoutNeighbors() {
    TrailCurve.Sample sample = TrailCurve.sample(null, A, B, null, 0.25);
    assertVec(new Vec3(0.25, 0, 0), sample.point());
    assertVec(new Vec3(1, 0, 0), sample.direction());
  }

  @Test
  void cutsTheCornerInsteadOfTouchingIt() {
    // The end of the first segment no longer reaches B: it rounds the corner toward C.
    TrailCurve.Sample sample = TrailCurve.sample(null, A, B, C, 1.0);
    assertVec(new Vec3(0.875, 0, 0.125), sample.point());
    // Halfway around a right angle, travel is diagonal.
    double half = Math.sqrt(0.5);
    assertVec(new Vec3(half, 0, half), sample.direction());
  }

  @Test
  void adjacentSegmentsJoinSeamlessly() {
    TrailCurve.Sample endOfFirst = TrailCurve.sample(null, A, B, C, 1.0);
    TrailCurve.Sample startOfSecond = TrailCurve.sample(A, B, C, null, 0.0);
    assertVec(endOfFirst.point(), startOfSecond.point());
    assertVec(endOfFirst.direction(), startOfSecond.direction());
  }

  @Test
  void curveIsTangentToTheStraightPart() {
    // Where the corner curve begins (at the segment's midpoint) it runs straight along it.
    TrailCurve.Sample sample = TrailCurve.sample(null, A, B, C, 0.5);
    assertVec(new Vec3(0.5, 0, 0), sample.point());
    assertVec(new Vec3(1, 0, 0), sample.direction());
  }

  @Test
  void cornerRadiusIsCappedOnLongSegments() {
    Vec3 far = new Vec3(10, 0, 0);
    Vec3 turn = new Vec3(10, 0, 10);
    // 1 block before the corner, past the 0.5-block radius, the trail is still straight.
    TrailCurve.Sample sample = TrailCurve.sample(null, A, far, turn, 0.9);
    assertVec(new Vec3(9, 0, 0), sample.point());
  }

  @Test
  void staysInsideTheCorner() {
    for (int i = 0; i <= 100; i++) {
      Vec3 point = TrailCurve.sample(null, A, B, C, i / 100.0).point();
      assertTrue(point.x() <= 1 + TOLERANCE && point.z() >= -TOLERANCE, point.toString());
    }
  }

  @Test
  void zeroLengthSegmentHasNoDirection() {
    TrailCurve.Sample sample = TrailCurve.sample(null, A, A, B, 0.5);
    assertVec(A, sample.point());
    assertVec(Vec3.ZERO, sample.direction());
  }

  private static void assertVec(Vec3 expected, Vec3 actual) {
    assertEquals(expected.x(), actual.x(), TOLERANCE, "x");
    assertEquals(expected.y(), actual.y(), TOLERANCE, "y");
    assertEquals(expected.z(), actual.z(), TOLERANCE, "z");
  }
}
