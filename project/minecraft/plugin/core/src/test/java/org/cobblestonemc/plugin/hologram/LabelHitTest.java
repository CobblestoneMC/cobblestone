/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.junit.jupiter.api.Test;

/** When {@link LabelHit} says a player's swing lands on a label. */
class LabelHitTest {

  private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);
  private static final Vec3 LABEL = new Vec3(5.5, 2.0, 0.5);
  private static final double HALF_WIDTH = LabelHit.halfWidth(6);

  /** A flat floor at y = -1. */
  private static final TrailSmoother.BlockProbe FLOOR =
      (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;

  private static OptionalDouble at(Vec3 target, TrailSmoother.BlockProbe probe) {
    return LabelHit.distance(EYE, target.minus(EYE).unit(), LABEL, HALF_WIDTH, probe);
  }

  @Test
  void hitsTheMiddle() {
    OptionalDouble distance = at(LABEL, FLOOR);
    assertTrue(distance.isPresent());
    assertEquals(Math.sqrt(LABEL.minus(EYE).lengthSquared()), distance.getAsDouble(), 1e-9);
  }

  @Test
  void hitsNearTheEdges() {
    assertTrue(at(LABEL.plus(new Vec3(0, 0, HALF_WIDTH - 0.01)), FLOOR).isPresent());
    assertTrue(at(LABEL.plus(new Vec3(0, LabelHit.HALF_HEIGHT - 0.01, 0)), FLOOR).isPresent());
  }

  @Test
  void missesBesideAboveAndBelow() {
    assertFalse(at(LABEL.plus(new Vec3(0, 0, HALF_WIDTH + 0.05)), FLOOR).isPresent());
    assertFalse(at(LABEL.plus(new Vec3(0, LabelHit.HALF_HEIGHT + 0.05, 0)), FLOOR).isPresent());
    assertFalse(at(LABEL.minus(new Vec3(0, LabelHit.HALF_HEIGHT + 0.05, 0)), FLOOR).isPresent());
  }

  @Test
  void missesLookingAway() {
    assertFalse(LabelHit.distance(EYE, new Vec3(-1, 0, 0), LABEL, HALF_WIDTH, FLOOR).isPresent());
  }

  @Test
  void hitsFromAnySide() {
    Vec3 eye = new Vec3(5.5, 1.62, 6.5); // south of the label, facing north
    Vec3 look = LABEL.minus(eye).unit();
    assertTrue(LabelHit.distance(eye, look, LABEL, HALF_WIDTH, FLOOR).isPresent());
  }

  @Test
  void missesThroughAWall() {
    assertFalse(at(LABEL, (x, y, z) -> x == 3 ? TrailBlock.SOLID : TrailBlock.OPEN).isPresent());
  }

  @Test
  void missesOutOfReach() {
    Vec3 far = new Vec3(EYE.x() + LabelHit.REACH + 1, 2.0, 0.5);
    Vec3 look = far.minus(EYE).unit();
    assertFalse(LabelHit.distance(EYE, look, far, HALF_WIDTH, FLOOR).isPresent());
  }
}
