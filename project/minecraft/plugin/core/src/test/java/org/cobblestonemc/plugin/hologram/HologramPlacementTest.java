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

import java.util.List;
import java.util.Optional;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;
import org.junit.jupiter.api.Test;

/** Where {@link HologramPlacement} puts a label, and when the player can see it. */
class HologramPlacementTest {

  private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);
  private static final Vec3 TRAIL = new Vec3(3.0, 0.9, 0.5);
  private static final Vec3 OVER = new Vec3(3.0, 0.9 + HologramPlacement.HEIGHT, 0.5);

  /** A flat floor at y = -1. */
  private static final TrailSmoother.BlockProbe FLOOR =
      (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;

  @Test
  void floatsOverTheTrailInTheOpen() {
    assertEquals(Optional.of(OVER), HologramPlacement.place(EYE, TRAIL, List.of(), FLOOR));
  }

  @Test
  void movesUpOutOfASolidBlock() {
    TrailSmoother.BlockProbe probe = solidAt(FLOOR, 3, 2, 0);
    assertEquals(
        Optional.of(OVER.plus(new Vec3(0, 1, 0))),
        HologramPlacement.place(EYE, TRAIL, List.of(), probe));
  }

  @Test
  void movesAsideUnderALowCeiling() {
    // A ceiling at y = 2 over the far side of the trail; only the block nearer the player is open.
    TrailSmoother.BlockProbe probe =
        (x, y, z) -> y < 0 || (y >= 2 && x >= 3) ? TrailBlock.SOLID : TrailBlock.OPEN;
    assertEquals(
        Optional.of(OVER.plus(new Vec3(-1, 0, 0))),
        HologramPlacement.place(EYE, TRAIL, List.of(), probe));
  }

  @Test
  void stacksOverAnotherLabel() {
    assertEquals(
        Optional.of(OVER.plus(new Vec3(0, HologramPlacement.LINE_SPACING, 0))),
        HologramPlacement.place(EYE, TRAIL, List.of(OVER), FLOOR));
  }

  @Test
  void nothingWhenEverySpotIsOutOfSight() {
    // A wall between the player and the trail from head height up.
    TrailSmoother.BlockProbe probe =
        (x, y, z) -> y < 0 || (x == 2 && y >= 2) ? TrailBlock.SOLID : TrailBlock.OPEN;
    assertEquals(Optional.empty(), HologramPlacement.place(EYE, TRAIL, List.of(), probe));
  }

  @Test
  void sightIsBlockedBySolidAndUnloadedBlocks() {
    Vec3 target = new Vec3(5.5, 1.62, 0.5);
    assertTrue(HologramPlacement.inSight(EYE, target, FLOOR));
    assertFalse(HologramPlacement.inSight(EYE, target, solidAt(FLOOR, 3, 1, 0)));
    assertFalse(
        HologramPlacement.inSight(
            EYE, target, (x, y, z) -> x == 4 ? TrailBlock.UNLOADED : TrailBlock.OPEN));
    assertTrue(
        HologramPlacement.inSight(EYE, target, solidAt(FLOOR, 0, 1, 0)),
        "the block the eyes are in doesn't count");
  }

  @Test
  void sightFollowsDiagonals() {
    Vec3 target = new Vec3(3.5, 3.5, 3.5);
    assertTrue(HologramPlacement.inSight(new Vec3(0.5, 0.5, 0.5), target, FLOOR));
    assertFalse(
        HologramPlacement.inSight(new Vec3(0.5, 0.5, 0.5), target, solidAt(FLOOR, 2, 2, 2)));
    assertTrue(
        HologramPlacement.inSight(new Vec3(0.5, 0.5, 0.5), target, solidAt(FLOOR, 2, 2, 0)),
        "a block beside the line");
  }

  @Test
  void theViewIsAnEllipseWiderThanItIsTall() {
    Gaze ahead = new Gaze(EYE, new Vec3(1, 0, 0));
    assertTrue(HologramPlacement.inView(ahead, new Vec3(4.5, 1.62, 0.5), 20, 10));
    assertTrue(HologramPlacement.inView(ahead, sideways(18), 20, 10), "18 degrees to the side");
    assertFalse(HologramPlacement.inView(ahead, sideways(22), 20, 10), "22 degrees to the side");
    assertTrue(HologramPlacement.inView(ahead, up(9), 20, 10), "9 degrees up");
    assertFalse(HologramPlacement.inView(ahead, up(12), 20, 10), "12 degrees up");
    assertFalse(
        HologramPlacement.inView(ahead, new Vec3(-3.5, 1.62, 0.5), 20, 10), "behind the player");
  }

  @Test
  void theViewWrapsAroundTheYawSeam() {
    // Looking just one side of due west, at a target just the other side.
    double just = Math.toRadians(5);
    Gaze west = new Gaze(EYE, new Vec3(-Math.cos(just), 0, Math.sin(just)));
    Vec3 target = EYE.plus(new Vec3(-Math.cos(just), 0, -Math.sin(just)).times(3));
    assertTrue(HologramPlacement.inView(west, target, 20, 10));
  }

  /** A point 3 blocks from the eyes, {@code degrees} to the side of due east. */
  private static Vec3 sideways(double degrees) {
    double angle = Math.toRadians(degrees);
    return EYE.plus(new Vec3(Math.cos(angle), 0, Math.sin(angle)).times(3));
  }

  /** A point 3 blocks from the eyes, {@code degrees} above due east. */
  private static Vec3 up(double degrees) {
    double angle = Math.toRadians(degrees);
    return EYE.plus(new Vec3(Math.cos(angle), Math.sin(angle), 0).times(3));
  }

  private static TrailSmoother.BlockProbe solidAt(
      TrailSmoother.BlockProbe base, int sx, int sy, int sz) {
    return (x, y, z) -> x == sx && y == sy && z == sz ? TrailBlock.SOLID : base.at(x, y, z);
  }
}
