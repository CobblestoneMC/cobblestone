/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When {@link LineOfSight} says a player can see a point. */
class LineOfSightTest {

  private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);

  /** A flat floor at y = -1. */
  private static final TrailSmoother.BlockProbe FLOOR =
      (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;

  @Test
  void sightIsBlockedBySolidAndUnloadedBlocks() {
    Vec3 target = new Vec3(5.5, 1.62, 0.5);
    assertTrue(LineOfSight.clear(EYE, target, FLOOR));
    assertFalse(LineOfSight.clear(EYE, target, solidAt(FLOOR, 3, 1, 0)));
    assertFalse(
        LineOfSight.clear(
            EYE, target, (x, y, z) -> x == 4 ? TrailBlock.UNLOADED : TrailBlock.OPEN));
    assertTrue(
        LineOfSight.clear(EYE, target, solidAt(FLOOR, 0, 1, 0)),
        "the block the eyes are in doesn't count");
  }

  @Test
  void sightFollowsDiagonals() {
    Vec3 target = new Vec3(3.5, 3.5, 3.5);
    assertTrue(LineOfSight.clear(new Vec3(0.5, 0.5, 0.5), target, FLOOR));
    assertFalse(LineOfSight.clear(new Vec3(0.5, 0.5, 0.5), target, solidAt(FLOOR, 2, 2, 2)));
    assertTrue(
        LineOfSight.clear(new Vec3(0.5, 0.5, 0.5), target, solidAt(FLOOR, 2, 2, 0)),
        "a block beside the line");
  }

  private static TrailSmoother.BlockProbe solidAt(
      TrailSmoother.BlockProbe base, int sx, int sy, int sz) {
    return (x, y, z) -> x == sx && y == sy && z == sz ? TrailBlock.SOLID : base.at(x, y, z);
  }
}
