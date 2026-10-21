/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How {@link TrailLeg} measures along a leg and where its trail starts. */
class TrailLegTest {

  private static final Vec3 EYE = new Vec3(0.5, 1.62, 0.5);

  /** A flat floor at y = -1. */
  private static final TrailSmoother.BlockProbe FLOOR =
      (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;

  /**
   * A straight, unsmoothed leg east along x from 0.5, {@code blocks} long, from half a block in.
   */
  private static TrailLeg straight(int blocks) {
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i <= blocks; i++) {
      nodes.add(new Vec3(i + 0.5, 0.9, 0.5));
    }
    SmoothedTrail trail = new SmoothedTrail(nodes, node -> false, node -> true);
    List<TrailLeg.Stretch> stretches = new ArrayList<>();
    for (int i = 0; i < blocks; i++) {
      stretches.add(new TrailLeg.Stretch(trail, i, i == 0 ? 0.5 : 0, true));
    }
    return new TrailLeg(stretches, true, blocks, false);
  }

  @Test
  void measuresFromPartWayAlongTheFirstSegment() {
    TrailLeg leg = straight(10);
    assertEquals(9.5, leg.length(), 1e-9);
    assertEquals(1.0, leg.at(0).x(), 1e-9);
    assertEquals(4.0, leg.at(3).x(), 1e-9);
    assertEquals(10.5, leg.at(100).x(), 1e-9, "past the end is the end");
  }

  @Test
  void theTrailStartsAtTheLeadInSight() {
    assertEquals(3, straight(10).head(3, EYE, FLOOR), 1e-9);
    assertEquals(9.5, straight(10).head(20, EYE, FLOOR), 1e-9, "no further than the leg");
  }

  @Test
  void outOfSightTheTrailStartsJustPastWhatCanBeSeen() {
    // A wall across the leg at x = 6 from the ground up.
    TrailSmoother.BlockProbe walled =
        (x, y, z) -> y < 0 || x == 6 ? TrailBlock.SOLID : TrailBlock.OPEN;
    double head = straight(10).head(8, EYE, walled);
    assertEquals(4 + TrailLeg.STUB, head, 1e-9); // 4 along (x = 5) is the last point in sight
  }

  @Test
  void drawsOnlyBetweenTheGivenPoints() {
    List<double[]> parts = new ArrayList<>();
    straight(10)
        .forEachDrawn(
            3, 6, (path, segment, from, to, length) -> parts.add(new double[] {segment, length}));
    double drawn = parts.stream().mapToDouble(part -> part[1]).sum();
    assertEquals(3, drawn, 1e-9);
    assertEquals(3, (int) parts.getFirst()[0], "starting in the segment 3 blocks along");
  }
}
