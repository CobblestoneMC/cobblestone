/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.List;

/**
 * Relaxes the trail's block-to-block nodes into a flowing line, as far as the surrounding blocks
 * allow.
 *
 * <p>Each free node is repeatedly pulled toward the midpoint of its neighbors (Laplacian smoothing,
 * sweeping alternately forward and backward so neither direction is favored). That straightens a
 * staircase of grid steps into a diagonal and widens a turn into a gradual sweep. A move is only
 * accepted if the trail as drawn around the node keeps {@code CLEARANCE} from every solid block:
 * the node, the segments to both neighbors, and the rounded corners {@link TrailCurve} draws at the
 * node and at both neighbors (whose shape the move changes too). Otherwise the move is halved and
 * retried, then abandoned. So the trail flows freely in open terrain but stays hugging the block
 * centers in a tight cave, and a bend is limited by whichever surface lies on its inside: the floor
 * over a crest, the ceiling at the foot of a slope. Every interior corner is checked as rounded,
 * even one the renderer leaves sharp (e.g. next to an action), which only holds the trail back a
 * little more there.
 *
 * <p>The path's own blocks — the block each node sits in and the one above it (the player's body
 * space) — count as open around that node and its two neighbors, since the path goes through them
 * there (including blocks it mines). Everywhere else on the trail they count as whatever they are,
 * so the trail never cuts through a block that a different step has yet to mine. No node drifts
 * more than {@code MAX_DEVIATION} from where it started, so the trail still shows which blocks to
 * walk on. A node whose block is {@link TrailBlock#UNLOADED} stays put and is reported as not
 * loaded; any unloaded block near a moving node blocks the move.
 */
public final class TrailSmoother {

  /** How far a node may drift from its original position, in blocks. */
  static final double MAX_DEVIATION = 1.0;

  /** The distance kept between the trail and any solid block, in blocks. */
  static final double CLEARANCE = 0.25;

  /** The most passes over the nodes. */
  private static final int ITERATIONS = 32;

  /** The fraction of the way a node moves toward its neighbors' midpoint in one pass. */
  private static final double RELAXATION = 0.5;

  /** How many times a blocked move is halved before it is given up. */
  private static final int BISECTIONS = 2;

  /** The spacing of a segment's clearance samples, in blocks; less than {@code CLEARANCE}. */
  private static final double SAMPLE_SPACING = 0.2;

  /** How many points of a rounded corner are checked for clearance, between its ends. */
  private static final int CORNER_SAMPLES = 5;

  /** Smoothing stops once no node moves further than this in a pass, in blocks. */
  private static final double CONVERGED = 1e-3;

  /** Reads the block at a position. */
  @FunctionalInterface
  public interface BlockProbe {
    /**
     * What occupies a block position.
     *
     * @param x the block's x-coordinate
     * @param y the block's y-coordinate
     * @param z the block's z-coordinate
     * @return what the trail sees there
     */
    TrailBlock at(int x, int y, int z);
  }

  /**
   * The smoothed nodes.
   *
   * @param points the smoothed positions, index-aligned with the input nodes
   * @param loaded whether each node's block was loaded; an unloaded node was left in place and
   *     should not be drawn
   * @param nearUnloaded whether moving a kept node read a block that wasn't loaded, which may have
   *     held it back: smoothing again once that block loads can go further
   */
  public record Result(List<Vec3> points, boolean[] loaded, boolean nearUnloaded) {}

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
    return smooth(nodes, nodes, pinned, new boolean[nodes.size()], probe);
  }

  /**
   * Smooths a run of trail nodes from where an earlier smoothing left them. The first and last
   * nodes are always held in place.
   *
   * @param anchors the original (block-centered) nodes, in path order: each node drifts at most
   *     {@code MAX_DEVIATION} from its anchor, and its anchor's blocks count as open around it
   * @param start where each node starts; the trail between them must already be clear
   * @param pinned which nodes must not move
   * @param provisional which nodes' results the caller discards, smoothed only as context for the
   *     others. A kept node near one also keeps the trail clear with that node at its start, since
   *     that is where the kept trail joins it.
   * @param probe reads the world; each block is read at most once per call
   * @return the smoothed nodes
   */
  static Result smooth(
      List<Vec3> anchors,
      List<Vec3> start,
      boolean[] pinned,
      boolean[] provisional,
      BlockProbe probe) {
    int n = anchors.size();
    var world = new BlockCache(probe);
    var corridor = new long[2 * n];
    var loaded = new boolean[n];
    for (int j = 0; j < n; j++) {
      var block = BlockPos.of(anchors.get(j));
      corridor[2 * j] = block.pack();
      corridor[2 * j + 1] = block.above().pack();
      loaded[j] = world.at(block.x(), block.y(), block.z()) != TrailBlock.UNLOADED;
    }
    var clearance = new Clearance(world, corridor);
    world.takeUnloaded(); // only reads made while moving nodes count

    Vec3[] current = start.toArray(new Vec3[0]);
    // The trail as the caller keeps it: provisional nodes stay at their starts.
    Vec3[] kept = current.clone();
    // Whether a provisional node shapes the trail drawn around kept node j.
    var nearProvisional = new boolean[n];
    for (int j = 0; j < n; j++) {
      for (int i = Math.max(0, j - 2); i <= Math.min(n - 1, j + 2); i++) {
        nearProvisional[j] |= !provisional[j] && provisional[i];
      }
    }
    boolean nearUnloaded = false;
    // A refused move is refused again until the node or a neighbor moves: same inputs, same answer.
    var stuck = new boolean[n];
    for (int iteration = 0; iteration < ITERATIONS; iteration++) {
      boolean forward = iteration % 2 == 0;
      double moved = 0;
      for (int k = 1; k < n - 1; k++) {
        int j = forward ? k : n - 1 - k;
        if (pinned[j] || !loaded[j] || stuck[j]) {
          continue;
        }
        Vec3 prev = current[j - 1];
        Vec3 next = current[j + 1];
        Vec3 target =
            current[j].plus(prev.plus(next).times(0.5).minus(current[j]).times(RELAXATION));
        Vec3 offset = target.minus(anchors.get(j));
        double deviation = offset.length();
        if (deviation > MAX_DEVIATION) {
          target = anchors.get(j).plus(offset.times(MAX_DEVIATION / deviation));
        }
        stuck[j] = true;
        for (int attempt = 0; attempt <= BISECTIONS; attempt++) {
          if (clearance.accepts(j, current, target)
              && (!nearProvisional[j] || clearance.accepts(j, kept, target))) {
            moved = Math.max(moved, target.minus(current[j]).length());
            current[j] = target;
            if (!provisional[j]) {
              kept[j] = target;
            }
            stuck[j - 1] = false;
            stuck[j] = false;
            stuck[j + 1] = false;
            break;
          }
          target = current[j].plus(target).times(0.5);
        }
        // A provisional node's reads don't count: its result is discarded.
        nearUnloaded |= world.takeUnloaded() && !provisional[j];
      }
      if (moved < CONVERGED) {
        break;
      }
    }
    return new Result(List.of(current), loaded, nearUnloaded);
  }

  /**
   * Clearance tests against the world, where the tests around node {@code j} count the corridor
   * blocks of nodes {@code j - 1} through {@code j + 1} as open.
   *
   * @param corridor each node's block and the one above it, packed: node {@code j}'s are at {@code
   *     2j} and {@code 2j + 1}
   */
  private record Clearance(BlockCache world, long[] corridor) {

    /**
     * Whether the trail drawn around node {@code j} at {@code point}, with the other nodes where
     * {@code trail} has them, is clear: the node, the segments to both neighbors, and the rounded
     * corners at the node and at both neighbors.
     */
    boolean accepts(int j, Vec3[] trail, Vec3 point) {
      Vec3 prev = trail[j - 1];
      Vec3 next = trail[j + 1];
      boolean aroundNode =
          openAround(j, prev, point, next)
              || (clearAt(point, j)
                  && clearBetween(prev, point, j)
                  && clearBetween(point, next, j)
                  && cornerClear(prev, point, next, j));
      return aroundNode
          && (j < 2 || neighborCornerClear(trail[j - 2], prev, point, j - 1))
          && (j + 2 >= trail.length || neighborCornerClear(point, next, trail[j + 2], j + 1));
    }

    /**
     * Whether the rounded corner at node {@code k}, at {@code b} between {@code a} and {@code c},
     * is clear. It ends at most halfway along each segment, so it lies in the triangle of {@code b}
     * and the two segments' midpoints.
     */
    private boolean neighborCornerClear(Vec3 a, Vec3 b, Vec3 c, int k) {
      return openAround(k, a.plus(b).times(0.5), b, b.plus(c).times(0.5))
          || cornerClear(a, b, c, k);
    }

    /** Whether the rounded corner at {@code b}, between {@code a} and {@code c}, is clear. */
    private boolean cornerClear(Vec3 a, Vec3 b, Vec3 c, int k) {
      for (int s = 1; s <= CORNER_SAMPLES; s++) {
        Vec3 point = TrailCurve.corner(a, b, c, (double) s / (CORNER_SAMPLES + 1));
        if (point == null) {
          return true; // not rounded: the segments are the whole trail here
        }
        if (!clearAt(point, k)) {
          return false;
        }
      }
      return true;
    }

    /**
     * Whether every block within {@code CLEARANCE} of the bounding box of {@code a}, {@code b} and
     * {@code c} is open for node {@code j}. Then everything in their triangle (segments and rounded
     * corner alike) is clear without sampling it, which is the common case in open terrain.
     */
    private boolean openAround(int j, Vec3 a, Vec3 b, Vec3 c) {
      int maxX = BlockPos.floor(Math.max(a.x(), Math.max(b.x(), c.x())) + CLEARANCE);
      int maxY = BlockPos.floor(Math.max(a.y(), Math.max(b.y(), c.y())) + CLEARANCE);
      int maxZ = BlockPos.floor(Math.max(a.z(), Math.max(b.z(), c.z())) + CLEARANCE);
      int minY = BlockPos.floor(Math.min(a.y(), Math.min(b.y(), c.y())) - CLEARANCE);
      int minZ = BlockPos.floor(Math.min(a.z(), Math.min(b.z(), c.z())) - CLEARANCE);
      for (int x = BlockPos.floor(Math.min(a.x(), Math.min(b.x(), c.x())) - CLEARANCE);
          x <= maxX;
          x++) {
        for (int y = minY; y <= maxY; y++) {
          for (int z = minZ; z <= maxZ; z++) {
            if (world.at(x, y, z) != TrailBlock.OPEN && !inCorridor(BlockPos.pack(x, y, z), j)) {
              return false;
            }
          }
        }
      }
      return true;
    }

    /** Whether the interior of the segment {@code a → b} is clear for node {@code j}. */
    private boolean clearBetween(Vec3 a, Vec3 b, int j) {
      Vec3 diff = b.minus(a);
      int samples = (int) Math.ceil(diff.length() / SAMPLE_SPACING);
      for (int i = 1; i < samples; i++) {
        if (!clearAt(a.plus(diff.times((double) i / samples)), j)) {
          return false;
        }
      }
      return true;
    }

    /** Whether no solid (or unreadable) block lies within {@code CLEARANCE} of {@code point}. */
    private boolean clearAt(Vec3 point, int j) {
      int maxX = BlockPos.floor(point.x() + CLEARANCE);
      int maxY = BlockPos.floor(point.y() + CLEARANCE);
      int maxZ = BlockPos.floor(point.z() + CLEARANCE);
      for (int x = BlockPos.floor(point.x() - CLEARANCE); x <= maxX; x++) {
        double dx = axisDistance(point.x(), x);
        for (int y = BlockPos.floor(point.y() - CLEARANCE); y <= maxY; y++) {
          double dy = axisDistance(point.y(), y);
          for (int z = BlockPos.floor(point.z() - CLEARANCE); z <= maxZ; z++) {
            double dz = axisDistance(point.z(), z);
            if (dx * dx + dy * dy + dz * dz >= CLEARANCE * CLEARANCE
                || world.at(x, y, z) == TrailBlock.OPEN
                || inCorridor(BlockPos.pack(x, y, z), j)) {
              continue;
            }
            return false;
          }
        }
      }
      return true;
    }

    /**
     * Whether {@code key} is a corridor block of node {@code j - 1}, {@code j} or {@code j + 1}.
     */
    private boolean inCorridor(long key, int j) {
      int end = Math.min(corridor.length, 2 * (j + 2));
      for (int i = Math.max(0, 2 * (j - 1)); i < end; i++) {
        if (corridor[i] == key) {
          return true;
        }
      }
      return false;
    }

    /** The distance from {@code value} to the unit interval starting at {@code block}. */
    private static double axisDistance(double value, int block) {
      return Math.max(0, Math.max(block - value, value - (block + 1)));
    }
  }

  /**
   * The probe's answers, each block read at most once. Open addressing over packed positions, so a
   * lookup in the hot loop neither boxes nor allocates.
   */
  private static final class BlockCache {

    private static final TrailBlock[] BLOCKS = TrailBlock.values();

    private final BlockProbe probe;
    private long[] keys = new long[256];
    private byte[] values = new byte[256]; // 0 = empty, otherwise ordinal + 1
    private int size;
    private boolean sawUnloaded;

    BlockCache(BlockProbe probe) {
      this.probe = probe;
    }

    TrailBlock at(int x, int y, int z) {
      long key = BlockPos.pack(x, y, z);
      int slot = slot(keys, values, key);
      TrailBlock block;
      if (values[slot] != 0) {
        block = BLOCKS[values[slot] - 1];
      } else {
        block = probe.at(x, y, z);
        keys[slot] = key;
        values[slot] = (byte) (block.ordinal() + 1);
        if (++size * 2 > keys.length) {
          grow();
        }
      }
      sawUnloaded |= block == TrailBlock.UNLOADED;
      return block;
    }

    /** Whether a lookup has returned {@link TrailBlock#UNLOADED} since the last call. */
    boolean takeUnloaded() {
      boolean saw = sawUnloaded;
      sawUnloaded = false;
      return saw;
    }

    private void grow() {
      long[] oldKeys = keys;
      byte[] oldValues = values;
      keys = new long[oldKeys.length * 2];
      values = new byte[oldValues.length * 2];
      for (int i = 0; i < oldKeys.length; i++) {
        if (oldValues[i] != 0) {
          int slot = slot(keys, values, oldKeys[i]);
          keys[slot] = oldKeys[i];
          values[slot] = oldValues[i];
        }
      }
    }

    /** The slot holding {@code key}, or the empty slot where it belongs. */
    private static int slot(long[] keys, byte[] values, long key) {
      int mask = keys.length - 1;
      int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
      while (values[slot] != 0 && keys[slot] != key) {
        slot = (slot + 1) & mask;
      }
      return slot;
    }
  }
}
