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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Lazy, budgeted, retrying chunk smoothing for {@link SmoothedTrail}. */
class SmoothedTrailTest {

  private static final TrailSmoother.BlockProbe OPEN_FIELD =
      (x, y, z) -> y < 0 ? TrailBlock.SOLID : TrailBlock.OPEN;

  @Test
  void smoothsOnlyWhatIsNeededAChunkAtATime() {
    var trail = new SmoothedTrail(line(70), node -> false);
    assertFalse(trail.drawable(0), "nothing is drawable before it is smoothed");

    trail.refresh(0, 69, 0, node -> true, OPEN_FIELD);
    assertTrue(trail.drawable(SmoothedTrail.CHUNK - 1));
    assertFalse(trail.drawable(SmoothedTrail.CHUNK), "the per-tick budget is one chunk");

    trail.refresh(0, 69, 1, node -> true, OPEN_FIELD);
    trail.refresh(0, 69, 2, node -> true, OPEN_FIELD);
    assertTrue(trail.drawable(69));
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
  void unavailableNodesWaitForAReset() {
    var trail = new SmoothedTrail(line(10), node -> false);
    trail.refresh(0, 9, 0, node -> node < 5, OPEN_FIELD);
    assertTrue(trail.drawable(4));
    assertFalse(trail.drawable(5));

    trail.refresh(0, 9, 100, node -> true, OPEN_FIELD);
    assertFalse(trail.drawable(5), "unavailable isn't retried on a timer");
    trail.resetUnavailable();
    trail.refresh(0, 9, 101, node -> true, OPEN_FIELD);
    assertTrue(trail.drawable(5));
  }

  @Test
  void chunksJoinWithoutCuttingBlocks() {
    // A long staircase through scattered pillars, with the world loading from the far end and the
    // chunks smoothed out of order, so every seam is joined both ways and some are redone.
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i < 4 * SmoothedTrail.CHUNK; i++) {
      nodes.add(new Vec3((i + 1) / 2 + 0.5, 0.9, i / 2 + 0.5));
    }
    var random = new Random(7);
    Set<TrailSmootherTest.Block> solid = new HashSet<>();
    for (int x = -3; x < 70; x++) {
      for (int z = -3; z < 70; z++) {
        solid.add(new TrailSmootherTest.Block(x, -1, z));
        if (random.nextInt(4) == 0) {
          solid.add(new TrailSmootherTest.Block(x, 0, z));
          solid.add(new TrailSmootherTest.Block(x, 1, z));
        }
      }
    }
    for (Vec3 node : nodes) {
      var block = TrailSmootherTest.blockOf(node);
      solid.remove(block);
      solid.remove(new TrailSmootherTest.Block(block.x(), 1, block.z()));
    }
    int[] loadedFrom = {40};
    TrailSmoother.BlockProbe world = TrailSmootherTest.probe(solid);
    TrailSmoother.BlockProbe probe =
        (x, y, z) -> x < loadedFrom[0] ? TrailBlock.UNLOADED : world.at(x, y, z);
    var trail = new SmoothedTrail(nodes, node -> false);

    int last = nodes.size() - 1;
    int tick = 0;
    for (int chunk : new int[] {3, 1, 2, 0}) {
      trail.refresh(chunk * SmoothedTrail.CHUNK, last, tick++, node -> true, probe);
    }
    loadedFrom[0] = -100;
    for (int i = 0; i < 4; i++) {
      tick += 20;
      trail.refresh(0, last, tick, node -> true, probe);
    }

    for (int i = 0; i <= last; i++) {
      assertTrue(trail.drawable(i), "node " + i + " is smoothed");
    }
    TrailSmootherTest.assertKeepsClear(nodes, trail.nodes(), solid);
  }

  private static List<Vec3> line(int length) {
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      nodes.add(new Vec3(i + 0.5, 0.9, 0.5));
    }
    return nodes;
  }
}
