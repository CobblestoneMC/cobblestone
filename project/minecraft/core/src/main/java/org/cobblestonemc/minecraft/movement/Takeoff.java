/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.ChunkSource;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * Taking off from the ground with a rocket: a boosted climb along a long, straight, clear line.
 *
 * <p>A rocket fired from the ground carries a glider up a straight line as long as its flight
 * lasts, {@link MovementCosts#TAKEOFF_BLOCKS_PER_DURATION} blocks for each level of Flight
 * Duration, using the longest the player holds. The line must be clear three blocks wide all the
 * way: a glider that clips a branch or a roof on the way up falls. It climbs at between 25 and 70
 * degrees above the horizon, steepest first, and the first clear line wins, tried towards the goal
 * first. One takeoff is offered at most, since a glider in the air can boost anywhere from where it
 * ends.
 *
 * <p>The line reaches well past the blocks an expansion reads, so it is read straight from the
 * chunks it crosses, a chunk at a time, stopping at the first block in the way.
 */
final class Takeoff {

  /** The climbs tried, in degrees above the horizon, steepest first. */
  private static final double[] PITCHES = {70, 45, 25};

  /** The eight compass headings, as unit steps. */
  private static final int[][] HEADINGS = {
    {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
  };

  /**
   * How far along the line before it must be three blocks wide: the first blocks leave the feet.
   */
  private static final int NARROW_START = 3;

  private Takeoff() {}

  /**
   * Offers a takeoff from {@code from}, where a player stands, if a clear line leaves it.
   *
   * @param rockets the longest Flight Duration among the rockets held, at least 1
   */
  static FutureOr<Edges> offer(
      ChunkSource chunks,
      MinecraftWorld world,
      Cell from,
      Cell goal,
      int rockets,
      TraversalState state,
      Edges edges) {
    int length = MovementCosts.TAKEOFF_BLOCKS_PER_DURATION * rockets;
    List<double[]> lines = new ArrayList<>(PITCHES.length * HEADINGS.length);
    for (double pitch : PITCHES) {
      for (int[] heading : towards(from, goal)) {
        double horizontal = Math.cos(Math.toRadians(pitch)) / Math.hypot(heading[0], heading[1]);
        lines.add(
            new double[] {
              heading[0] * horizontal, Math.sin(Math.toRadians(pitch)), heading[1] * horizontal
            });
      }
    }
    return new Reader(chunks, world, goal)
        .firstClear(from.plus(0, 1, 0), lines, 0, length)
        .map(
            end -> {
              if (end != null) {
                edges.add(
                    end,
                    MovementCosts.BOOST * length,
                    MinecraftStepType.ELYTRA,
                    state.with(MinecraftKeys.GLIDING, true));
              }
              return edges;
            });
  }

  /** The headings, those pointing most nearly at the goal first. */
  private static List<int[]> towards(Cell from, Cell goal) {
    double gx = goal.x() - from.x();
    double gz = goal.z() - from.z();
    List<int[]> sorted = new ArrayList<>(List.of(HEADINGS));
    sorted.sort(
        (a, b) ->
            Double.compare(
                -(a[0] * gx + a[1] * gz) / Math.hypot(a[0], a[1]),
                -(b[0] * gx + b[1] * gz) / Math.hypot(b[0], b[1])));
    return sorted;
  }

  /** Reads blocks straight from their chunks, keeping each chunk once fetched. */
  private static final class Reader {
    private final ChunkSource chunks;
    private final MinecraftWorld world;
    private final Cell goal;
    // Chunks may arrive on other threads, so the map is concurrent.
    private final Map<Long, MinecraftChunk> loaded = new ConcurrentHashMap<>();

    Reader(ChunkSource chunks, MinecraftWorld world, Cell goal) {
      this.chunks = chunks;
      this.world = world;
      this.goal = goal;
    }

    /** The end of the first of {@code lines}, from index {@code i}, that is clear; else null. */
    FutureOr<Cell> firstClear(Cell head, List<double[]> lines, int i, int length) {
      if (i == lines.size()) {
        return FutureOr.of(null);
      }
      double[] line = lines.get(i);
      return clear(head, line, 1, length)
          .flatMap(
              clear ->
                  clear
                      ? FutureOr.of(at(head, line, length))
                      : firstClear(head, lines, i + 1, length));
    }

    /** Whether the line is clear from step {@code t} on, fetching chunks as it reaches them. */
    private FutureOr<Boolean> clear(Cell head, double[] line, int t, int length) {
      for (; t <= length; t++) {
        Cell center = at(head, line, t);
        int reach = t < NARROW_START ? 0 : 1;
        Map<Long, Cell> missing = new LinkedHashMap<>();
        for (int dx = -reach; dx <= reach; dx++) {
          for (int dz = -reach; dz <= reach; dz++) {
            Cell column = center.plus(dx, 0, dz);
            long key = key(column);
            if (!loaded.containsKey(key)) {
              missing.put(key, column);
            }
          }
        }
        if (!missing.isEmpty()) {
          int resume = t;
          return fetch(missing).flatMap(ignored -> clear(head, line, resume, length));
        }
        for (int dx = -reach; dx <= reach; dx++) {
          for (int dy = -reach; dy <= reach; dy++) {
            for (int dz = -reach; dz <= reach; dz++) {
              if (!passable(center.plus(dx, dy, dz))) {
                return FutureOr.of(false);
              }
            }
          }
        }
      }
      return FutureOr.of(true);
    }

    private FutureOr<Void> fetch(Map<Long, Cell> missing) {
      List<CompletableFuture<Void>> pending = null;
      for (Map.Entry<Long, Cell> entry : missing.entrySet()) {
        FutureOr<MinecraftChunk> chunk = chunks.chunk(entry.getValue(), world, goal);
        if (chunk.isImmediate()) {
          loaded.put(entry.getKey(), chunk.value());
        } else {
          if (pending == null) {
            pending = new ArrayList<>();
          }
          long key = entry.getKey();
          pending.add(chunk.future().thenAccept(c -> loaded.put(key, c)));
        }
      }
      if (pending == null) {
        return FutureOr.of(null);
      }
      return FutureOr.ofFuture(
          CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])));
    }

    /** Above the world is open sky; below it, or unknown, is in the way. */
    private boolean passable(Cell cell) {
      if (cell.y() > world.maxY()) {
        return true;
      }
      if (cell.y() < world.minY()) {
        return false;
      }
      return loaded.get(key(cell)).block(cell.x() & 15, cell.y(), cell.z() & 15).isPassable();
    }

    private static Cell at(Cell head, double[] line, int t) {
      return new Cell(
          head.x() + (int) Math.round(line[0] * t),
          head.y() + (int) Math.round(line[1] * t),
          head.z() + (int) Math.round(line[2] * t));
    }

    private static long key(Cell cell) {
      return ((long) (cell.x() >> 4) << 32) ^ ((cell.z() >> 4) & 0xffffffffL);
    }
  }
}
