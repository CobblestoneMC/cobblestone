/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Flow in the open, restraint near blocks, and held nodes for {@link TrailSmoother}. */
class TrailSmootherTest {

  /** Block positions in a test world. */
  private record Block(int x, int y, int z) {}

  // The smoother samples segments every 0.2 blocks, so between samples the trail can come a little
  // closer to a block corner than CLEARANCE: at worst sqrt(0.25² - 0.1²) ≈ 0.229.
  private static final double SAMPLED_CLEARANCE = 0.22;

  @Test
  void staircaseInTheOpenBecomesADiagonal() {
    // Alternate east and south steps across a flat field: a zigzag whose nodes alternate between 0
    // and 0.71 blocks off the diagonal x = z.
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i <= 20; i++) {
      nodes.add(render((i + 1) / 2, 0, i / 2));
    }
    Set<Block> solid = floor(-5, 20);

    List<Vec3> smoothed = smooth(nodes, solid).points();

    for (int i = 4; i <= 16; i++) {
      double wobble = Math.abs(offDiagonal(smoothed.get(i)) - offDiagonal(smoothed.get(i + 1)));
      assertTrue(wobble < 0.05, "nodes " + i + " and " + (i + 1) + " still zigzag by " + wobble);
      assertEquals(0.9, smoothed.get(i).y(), 1e-9, "stays at body height on flat ground");
    }
    assertKeepsClear(nodes, smoothed, solid);
  }

  @Test
  void straightLineStaysPut() {
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i <= 10; i++) {
      nodes.add(render(i, 0, 0));
    }
    List<Vec3> smoothed = smooth(nodes, floor(-5, 15)).points();
    for (int i = 0; i <= 10; i++) {
      assertVec(nodes.get(i), smoothed.get(i));
    }
  }

  @Test
  void openTurnSweepsWide() {
    // East 10 blocks, then south 10: in the open the corner is cut by up to MAX_DEVIATION.
    List<Vec3> nodes = new ArrayList<>();
    for (int x = 0; x <= 10; x++) {
      nodes.add(render(x, 0, 0));
    }
    for (int z = 1; z <= 10; z++) {
      nodes.add(render(10, 0, z));
    }
    Set<Block> solid = floor(-5, 15);

    List<Vec3> smoothed = smooth(nodes, solid).points();

    Vec3 corner = smoothed.get(10);
    double cut = corner.minus(nodes.get(10)).length();
    assertTrue(cut > 0.5, "corner cut only " + cut);
    assertTrue(cut <= TrailSmoother.MAX_DEVIATION + 1e-9, "corner cut " + cut);
    assertKeepsClear(nodes, smoothed, solid);
  }

  @Test
  void tunnelTurnStaysTight() {
    // The same kind of turn, but in a 1-wide, 2-tall tunnel: the walls hold the corner in.
    List<Vec3> nodes = new ArrayList<>();
    for (int x = 0; x <= 5; x++) {
      nodes.add(render(x, 0, 0));
    }
    for (int z = 1; z <= 5; z++) {
      nodes.add(render(5, 0, z));
    }
    Set<Block> solid = new HashSet<>();
    for (int x = -3; x <= 8; x++) {
      for (int z = -3; z <= 8; z++) {
        for (int y = -1; y <= 2; y++) {
          solid.add(new Block(x, y, z));
        }
      }
    }
    for (Vec3 node : nodes) {
      solid.remove(blockOf(node));
      solid.remove(new Block((int) Math.floor(node.x()), 1, (int) Math.floor(node.z())));
    }

    List<Vec3> smoothed = smooth(nodes, solid).points();

    // Nothing strays toward the middle of a wall; the corner can cut diagonally toward the inside
    // block's edge, which is further away than the walls.
    for (int i = 0; i < nodes.size(); i++) {
      Vec3 moved = smoothed.get(i).minus(nodes.get(i));
      assertTrue(
          Math.abs(moved.x()) < 0.3 && Math.abs(moved.z()) < 0.3, "node " + i + " moved " + moved);
    }
    assertTrue(smoothed.get(5).minus(nodes.get(5)).length() > 0.05, "the corner still rounds");
    assertKeepsClear(nodes, smoothed, solid);
  }

  @Test
  void bendsAreLimitedByTheFloorAndCeilingOnTheirInside() {
    // Up a 1-block step onto a 3-long plateau and back down, in a 3-tall cave.
    int[] ground = {0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0};
    List<Vec3> nodes = new ArrayList<>();
    Set<Block> solid = new HashSet<>();
    for (int x = -3; x < ground.length + 3; x++) {
      int height = x >= 0 && x < ground.length ? ground[x] : 0;
      for (int y = -2; y < height; y++) {
        solid.add(new Block(x, y, 0));
      }
      solid.add(new Block(x, height + 3, 0));
      solid.add(new Block(x, height + 4, 0));
    }
    for (int x = 0; x < ground.length; x++) {
      nodes.add(render(x, ground[x], 0));
    }

    List<Vec3> smoothed = smooth(nodes, solid).points();

    // At the foot of the step (concave), the trail rises into the open air above it.
    assertTrue(smoothed.get(3).y() > nodes.get(3).y() + 0.05, "concave bend " + smoothed.get(3));
    // Over the lip (convex), it sinks toward the step, but not into it.
    assertTrue(smoothed.get(4).y() < nodes.get(4).y(), "convex bend " + smoothed.get(4));
    assertKeepsClear(nodes, smoothed, solid);
  }

  @Test
  void minedBlocksCountAsOpen() {
    // The zigzag mines a block: the path goes through it, so it does not hold the trail in place.
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i <= 12; i++) {
      nodes.add(render((i + 1) / 2, 0, i / 2));
    }
    Set<Block> solid = floor(-5, 15);
    Vec3 mined = nodes.get(5); // off the diagonal, so it must move
    solid.add(blockOf(mined));
    solid.add(new Block((int) Math.floor(mined.x()), 1, (int) Math.floor(mined.z())));

    List<Vec3> smoothed = smooth(nodes, solid).points();

    assertTrue(smoothed.get(5).minus(mined).length() > 0.1, "mined node still smooths");
  }

  @Test
  void pinnedAndUnloadedNodesStayPut() {
    List<Vec3> nodes = new ArrayList<>();
    for (int i = 0; i <= 20; i++) {
      nodes.add(render((i + 1) / 2, 0, i / 2));
    }
    Set<Block> solid = floor(-5, 20);
    var pinned = new boolean[nodes.size()];
    pinned[5] = true;
    TrailSmoother.BlockProbe probe =
        (x, y, z) ->
            x >= 8
                ? TrailBlock.UNLOADED
                : solid.contains(new Block(x, y, z)) ? TrailBlock.SOLID : TrailBlock.OPEN;

    TrailSmoother.Result result = TrailSmoother.smooth(nodes, pinned, probe);

    assertVec(nodes.get(5), result.points().get(5));
    assertTrue(result.loaded()[5]);
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).x() >= 8) {
        assertFalse(result.loaded()[i], "node " + i + " is unloaded");
        assertVec(nodes.get(i), result.points().get(i));
      }
    }
    // Between the held nodes, the trail still smooths.
    assertTrue(result.points().get(10).minus(nodes.get(10)).length() > 0.1);
  }

  // --- helpers ----------------------------------------------------------------

  private static TrailSmoother.Result smooth(List<Vec3> nodes, Set<Block> solid) {
    return TrailSmoother.smooth(
        nodes,
        new boolean[nodes.size()],
        (x, y, z) -> solid.contains(new Block(x, y, z)) ? TrailBlock.SOLID : TrailBlock.OPEN);
  }

  /** A flat floor (the layer at y = -1) spanning {@code min..max} in x and z. */
  private static Set<Block> floor(int min, int max) {
    Set<Block> solid = new HashSet<>();
    for (int x = min; x <= max; x++) {
      for (int z = min; z <= max; z++) {
        solid.add(new Block(x, -1, z));
      }
    }
    return solid;
  }

  private static double offDiagonal(Vec3 point) {
    return (point.x() - point.z()) / Math.sqrt(2);
  }

  /** The render point for a player standing in block {@code (x, y, z)}. */
  private static Vec3 render(int x, int y, int z) {
    return new Vec3(x + 0.5, y + 0.9, z + 0.5);
  }

  private static Block blockOf(Vec3 point) {
    return new Block(
        (int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
  }

  /**
   * Asserts the smoothed polyline keeps clear of every solid block the path doesn't pass through.
   */
  private static void assertKeepsClear(List<Vec3> original, List<Vec3> smoothed, Set<Block> solid) {
    Set<Block> corridor = new HashSet<>();
    for (Vec3 node : original) {
      Block block = blockOf(node);
      corridor.add(block);
      corridor.add(new Block(block.x(), block.y() + 1, block.z()));
    }
    for (int i = 0; i + 1 < smoothed.size(); i++) {
      Vec3 a = smoothed.get(i);
      Vec3 diff = smoothed.get(i + 1).minus(a);
      for (int s = 0; s <= 50; s++) {
        Vec3 point = a.plus(diff.times(s / 50.0));
        for (Block block : solid) {
          if (corridor.contains(block)) {
            continue;
          }
          double dx = Math.max(0, Math.max(block.x() - point.x(), point.x() - block.x() - 1));
          double dy = Math.max(0, Math.max(block.y() - point.y(), point.y() - block.y() - 1));
          double dz = Math.max(0, Math.max(block.z() - point.z(), point.z() - block.z() - 1));
          double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
          assertTrue(distance >= SAMPLED_CLEARANCE, point + " is " + distance + " from " + block);
        }
      }
    }
  }

  private static void assertVec(Vec3 expected, Vec3 actual) {
    assertEquals(expected.x(), actual.x(), 1e-9, "x");
    assertEquals(expected.y(), actual.y(), 1e-9, "y");
    assertEquals(expected.z(), actual.z(), 1e-9, "z");
  }
}
