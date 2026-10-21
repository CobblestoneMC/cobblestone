/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    TrailSmoother.BlockProbe probe = solidAt(FLOOR, 3, 1, 0);
    assertEquals(
        Optional.of(OVER.plus(new Vec3(0, 1, 0))),
        HologramPlacement.place(EYE, TRAIL, List.of(), probe));
  }

  @Test
  void movesAsideUnderALowCeiling() {
    // A ceiling at y = 1 over the far side of the trail; only the block nearer the player is open.
    TrailSmoother.BlockProbe probe =
        (x, y, z) -> y < 0 || (y >= 1 && x >= 3) ? TrailBlock.SOLID : TrailBlock.OPEN;
    assertEquals(
        Optional.of(OVER.plus(new Vec3(-1, 0, 0))),
        HologramPlacement.place(EYE, TRAIL, List.of(), probe));
  }

  @Test
  void stacksOverAnotherLabel() {
    assertEquals(
        Optional.of(OVER.plus(new Vec3(0, HologramPlacement.LABEL_SPACING, 0))),
        HologramPlacement.place(EYE, TRAIL, List.of(OVER), FLOOR));
  }

  @Test
  void nothingWhenEverySpotIsOutOfSight() {
    // A wall between the player and the trail from head height up.
    TrailSmoother.BlockProbe probe =
        (x, y, z) -> y < 0 || (x == 2 && y >= 1) ? TrailBlock.SOLID : TrailBlock.OPEN;
    assertEquals(Optional.empty(), HologramPlacement.place(EYE, TRAIL, List.of(), probe));
  }

  private static TrailSmoother.BlockProbe solidAt(
      TrailSmoother.BlockProbe base, int sx, int sy, int sz) {
    return (x, y, z) -> x == sx && y == sy && z == sz ? TrailBlock.SOLID : base.at(x, y, z);
  }
}
