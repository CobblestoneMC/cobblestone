/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lazy, budgeted, retrying chunk smoothing for {@link SmoothedTrail}. */
class SmoothedTrailTest {

  private static final TrailSmoother.BlockProbe OPEN_FIELD =
      (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;

  @Test
  void smoothsOnlyWhatIsNeededAFewChunksAtATime() {
    var trail = new SmoothedTrail(line(100), node -> false);
    assertFalse(trail.drawable(0), "nothing is drawable before it is smoothed");

    trail.refresh(0, 99, 0, node -> true, OPEN_FIELD);
    assertTrue(trail.drawable(2 * SmoothedTrail.CHUNK - 1));
    assertFalse(trail.drawable(2 * SmoothedTrail.CHUNK), "the per-tick budget is two chunks");

    trail.refresh(0, 99, 1, node -> true, OPEN_FIELD);
    assertTrue(trail.drawable(99));
  }

  @Test
  void retriesUnloadedChunksUntilTheyLoad() {
    var trail = new SmoothedTrail(line(10), node -> false);
    boolean[] loaded = {false};
    TrailSmoother.BlockProbe probe =
        (x, y, z) -> loaded[0] ? OPEN_FIELD.at(x, y, z) : TrailBlock.UNLOADED;

    trail.refresh(0, 9, 0, node -> true, probe);
    assertFalse(trail.drawable(5));

    loaded[0] = true;
    trail.refresh(0, 9, 5, node -> true, probe);
    assertFalse(trail.drawable(5), "not retried before the cooldown");
    trail.refresh(0, 9, 20, node -> true, probe);
    assertTrue(trail.drawable(5));
  }

  @Test
  void unavailableNodesAreNotDrawable() {
    var trail = new SmoothedTrail(line(10), node -> false);
    trail.refresh(0, 9, 0, node -> node < 5, OPEN_FIELD);
    assertTrue(trail.drawable(4));
    assertFalse(trail.drawable(5));
  }

  private static List<Vec3> line(int length) {
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      nodes.add(new Vec3(i + 0.5, 0.9, 0.5));
    }
    return nodes;
  }
}
