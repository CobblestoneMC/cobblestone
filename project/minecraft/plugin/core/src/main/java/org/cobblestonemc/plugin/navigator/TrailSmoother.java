/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relaxes the trail's block-to-block nodes into a flowing line, as far as the surrounding blocks
 * allow.
 *
 * <p>Each free node is repeatedly pulled toward the midpoint of its neighbors (Laplacian smoothing,
 * sweeping alternately forward and backward so neither direction is favored). That straightens a
 * staircase of grid steps into a diagonal and widens a turn into a gradual sweep. A move is only
 * accepted if the node, and the segments to both neighbors, keep {@code CLEARANCE} from every solid
 * block; otherwise the move is halved and retried, then abandoned. So the trail flows freely in
 * open terrain but stays hugging the block centers in a tight cave, and a bend is limited by
 * whichever surface lies on its inside: the floor over a crest, the ceiling at the foot of a slope.
 *
 * <p>The path's own blocks — the block each node sits in and the one above it (the player's body
 * space) — always count as open, since the path says to go through them (including blocks it
 * mines). No node drifts more than {@code MAX_DEVIATION} from where it started, so the trail still
 * shows which blocks to walk on. A node whose block is {@link TrailBlock#UNLOADED} stays put and is
 * reported as not loaded; any unloaded block near a moving node blocks the move.
 */
public final class TrailSmoother {

  /** How far a node may drift from its original position, in blocks. */
  static final double MAX_DEVIATION = 1.0;

  /** The distance kept between the trail and any solid block, in blocks. */
  static final double CLEARANCE = 0.25;

  private static final int ITERATIONS = 32;
  private static final double RELAXATION =
      0.5; // fraction of the way toward the neighbors' midpoint
  private static final int BISECTIONS = 2; // halvings of a blocked move before giving up on it
  private static final double SAMPLE_SPACING = 0.2; // segment clearance sampling step, < CLEARANCE
  private static final double CONVERGED = 1e-3; // stop once no node moves further than this

  /** Reads the block at a position. */
  @FunctionalInterface
  public interface BlockProbe {
    TrailBlock at(int x, int y, int z);
  }

  /**
   * The smoothed nodes.
   *
   * @param points the smoothed positions, index-aligned with the input nodes
   * @param loaded whether each node's block was loaded; an unloaded node was left in place and
   *     should not be drawn
   */
  public record Result(List<Vec3> points, boolean[] loaded) {}

  private TrailSmoother() {}

  /**
   * Smooths a run of trail nodes. The first and last nodes are always held in place.
   *
   * @param nodes the original (block-centered) nodes, in path order
   * @param pinned which nodes must not move (e.g. at an action step), index-aligned with {@code
   *     nodes}
   * @param probe reads the world; each block is read at most once per call
   * @return the smoothed nodes
   */
  public static Result smooth(List<Vec3> nodes, boolean[] pinned, BlockProbe probe) {
    int n = nodes.size();
    Map<Long, TrailBlock> cache = new HashMap<>();
    BlockProbe cached = (x, y, z) -> cache.computeIfAbsent(pack(x, y, z), k -> probe.at(x, y, z));
    Set<Long> corridor = new HashSet<>();
    var loaded = new boolean[n];
    for (int j = 0; j < n; j++) {
      Vec3 node = nodes.get(j);
      int x = floor(node.x());
      int y = floor(node.y());
      int z = floor(node.z());
      corridor.add(pack(x, y, z));
      corridor.add(pack(x, y + 1, z));
      loaded[j] = cached.at(x, y, z) != TrailBlock.UNLOADED;
    }
    var clearance = new Clearance(cached, corridor);

    Vec3[] current = nodes.toArray(new Vec3[0]);
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      boolean forward = iteration % 2 == 0;
      double moved = 0;
      for (int k = 1; k < n - 1; k++) {
        int j = forward ? k : n - 1 - k;
        if (pinned[j] || !loaded[j]) {
          continue;
        }
        Vec3 prev = current[j - 1];
        Vec3 next = current[j + 1];
        Vec3 target =
            current[j].plus(prev.plus(next).times(0.5).minus(current[j]).times(RELAXATION));
        Vec3 offset = target.minus(nodes.get(j));
        double deviation = offset.length();
        if (deviation > MAX_DEVIATION) {
          target = nodes.get(j).plus(offset.times(MAX_DEVIATION / deviation));
        }
        for (int attempt = 0; attempt <= BISECTIONS; attempt++) {
          if (clearance.accepts(prev, target, next)) {
            moved = Math.max(moved, target.minus(current[j]).length());
            current[j] = target;
            break;
          }
          target = current[j].plus(target).times(0.5);
        }
      }
      if (moved < CONVERGED) {
        break;
      }
    }
    return new Result(List.of(current), loaded);
  }

  /** Clearance tests against the world, with the path's own blocks counted as open. */
  private record Clearance(BlockProbe probe, Set<Long> corridor) {

    /** Whether {@code point}, and the segments joining it to both neighbors, are clear. */
    boolean accepts(Vec3 prev, Vec3 point, Vec3 next) {
      return clearAt(point) && clearBetween(prev, point) && clearBetween(point, next);
    }

    /** Whether the interior of the segment {@code a → b} is clear, sampled finely. */
    private boolean clearBetween(Vec3 a, Vec3 b) {
      Vec3 diff = b.minus(a);
      int samples = (int) Math.ceil(diff.length() / SAMPLE_SPACING);
      for (int i = 1; i < samples; i++) {
        if (!clearAt(a.plus(diff.times((double) i / samples)))) {
          return false;
        }
      }
      return true;
    }

    /** Whether no solid (or unreadable) block lies within {@code CLEARANCE} of {@code point}. */
    private boolean clearAt(Vec3 point) {
      for (int x = floor(point.x() - CLEARANCE); x <= floor(point.x() + CLEARANCE); x++) {
        for (int y = floor(point.y() - CLEARANCE); y <= floor(point.y() + CLEARANCE); y++) {
          for (int z = floor(point.z() - CLEARANCE); z <= floor(point.z() + CLEARANCE); z++) {
            if (corridor.contains(pack(x, y, z)) || probe.at(x, y, z) == TrailBlock.OPEN) {
              continue;
            }
            double dx = axisDistance(point.x(), x);
            double dy = axisDistance(point.y(), y);
            double dz = axisDistance(point.z(), z);
            if (dx * dx + dy * dy + dz * dz < CLEARANCE * CLEARANCE) {
              return false;
            }
          }
        }
      }
      return true;
    }

    /** The distance from {@code value} to the unit interval starting at {@code block}. */
    private static double axisDistance(double value, int block) {
      return Math.max(0, Math.max(block - value, value - (block + 1)));
    }
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }

  /** Packs a block position into a long (26 bits x, 26 bits z, 12 bits y). */
  private static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }
}
