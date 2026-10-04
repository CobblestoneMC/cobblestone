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
 * accepted if the node, and the segments to both neighbors, keep {@code CLEARANCE} from every solid
 * block; otherwise the move is halved and retried, then abandoned. So the trail flows freely in
 * open terrain but stays hugging the block centers in a tight cave, and a bend is limited by
 * whichever surface lies on its inside: the floor over a crest, the ceiling at the foot of a slope.
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

  /** Smoothing stops once no node moves further than this in a pass, in blocks. */
  private static final double CONVERGED = 1e-3;

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
   *     others. A kept node next to one also keeps its segment to that node's start clear, since
   *     the trail joins it there.
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

    Vec3[] current = start.toArray(new Vec3[0]);
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
          if (clearance.accepts(j, prev, target, next)
              && (provisional[j] || joinsProvisional(clearance, j, target, start, provisional))) {
            moved = Math.max(moved, target.minus(current[j]).length());
            current[j] = target;
            stuck[j - 1] = false;
            stuck[j] = false;
            stuck[j + 1] = false;
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

  /**
   * Whether kept node {@code j} at {@code point} joins clear to any provisional neighbor's start.
   */
  private static boolean joinsProvisional(
      Clearance clearance, int j, Vec3 point, List<Vec3> start, boolean[] provisional) {
    return (!provisional[j - 1] || clearance.clearBetween(start.get(j - 1), point, j))
        && (!provisional[j + 1] || clearance.clearBetween(point, start.get(j + 1), j));
  }

  /**
   * Clearance tests against the world, where node {@code j}'s tests count the corridor blocks of
   * nodes {@code j - 1} through {@code j + 1} as open.
   *
   * @param corridor each node's block and the one above it, packed: node {@code j}'s are at {@code
   *     2j} and {@code 2j + 1}
   */
  private record Clearance(BlockCache world, long[] corridor) {

    /** Whether node {@code j} at {@code point}, and the segments to both neighbors, are clear. */
    boolean accepts(int j, Vec3 prev, Vec3 point, Vec3 next) {
      return openAround(j, prev, point, next)
          || (clearAt(point, j) && clearBetween(prev, point, j) && clearBetween(point, next, j));
    }

    /**
     * Whether every block within {@code CLEARANCE} of the bounding box of {@code a}, {@code b} and
     * {@code c} is open for node {@code j}. Then both segments are clear without sampling them,
     * which is the common case in open terrain.
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
    boolean clearBetween(Vec3 a, Vec3 b, int j) {
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

    BlockCache(BlockProbe probe) {
      this.probe = probe;
    }

    TrailBlock at(int x, int y, int z) {
      long key = BlockPos.pack(x, y, z);
      int slot = slot(keys, values, key);
      if (values[slot] != 0) {
        return BLOCKS[values[slot] - 1];
      }
      TrailBlock block = probe.at(x, y, z);
      keys[slot] = key;
      values[slot] = (byte) (block.ordinal() + 1);
      if (++size * 2 > keys.length) {
        grow();
      }
      return block;
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
