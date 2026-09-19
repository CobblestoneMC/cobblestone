/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import org.cobblestonemc.Cell;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which chunks a solve is about to want: those intersecting a column of radius {@link
 * #LATERAL_RADIUS} running from a cell towards the solve's destination.
 *
 * <p>Pure geometry, kept apart from {@link ChunkProvider} so that the cache has one job. Read-ahead
 * is directional on purpose: a solve advances towards its destination, so chunks behind it are work
 * that would almost never be used.
 */
final class ReadAheadColumn {

  /**
   * Lateral half-width, in blocks, of the column. Most modes read a block or so to either side of
   * the cell they are expanding from, so a five-block-wide column is enough of a buffer to have
   * what they ask for next without dragging in chunks they will never touch.
   */
  static final int LATERAL_RADIUS = 2;

  /** Receives the chunk coordinates of each chunk the column reaches. */
  @FunctionalInterface
  interface ChunkVisitor {

    /**
     * Visits one chunk.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     */
    void visit(int chunkX, int chunkZ);
  }

  private ReadAheadColumn() {}

  /**
   * Visits every chunk, other than the cell's own, that the column from {@code cell} towards {@code
   * destination} reaches — as far as {@code distance} blocks, or as far as the destination if it is
   * nearer.
   *
   * <p>The column starts a lateral radius <em>behind</em> the cell so that a cell sitting right on
   * a chunk border still pulls in the chunk it just came out of, whose blocks its own expansion may
   * read. With no destination to aim at, the column degenerates to a disc around the cell: enough
   * to cover a border cell's neighbors, and nothing speculative beyond that.
   *
   * @param cell the cell the solve is at
   * @param destination where the solve is heading, or {@code null} if nowhere in particular
   * @param distance how far ahead to reach, in blocks; must be positive
   * @param visitor what to do with each chunk the column reaches
   */
  static void forEachChunk(
      Cell cell, @Nullable Cell destination, int distance, ChunkVisitor visitor) {
    double radius = LATERAL_RADIUS;
    // Block centers: a cell's coordinates name the lower corner of a unit cube.
    double startX = cell.x() + 0.5;
    double startZ = cell.z() + 0.5;
    double endX = startX;
    double endZ = startZ;
    if (destination != null) {
      double deltaX = destination.x() - cell.x();
      double deltaZ = destination.z() - cell.z();
      double length = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
      if (length > 1e-6) {
        double unitX = deltaX / length;
        double unitZ = deltaZ / length;
        double reach = Math.min(length, distance);
        endX = startX + unitX * reach;
        endZ = startZ + unitZ * reach;
        startX -= unitX * radius;
        startZ -= unitZ * radius;
      }
    }

    int centerChunkX = cell.x() >> 4;
    int centerChunkZ = cell.z() >> 4;
    int minChunkX = Math.floorDiv((int) Math.floor(Math.min(startX, endX) - radius), 16);
    int maxChunkX = Math.floorDiv((int) Math.floor(Math.max(startX, endX) + radius), 16);
    int minChunkZ = Math.floorDiv((int) Math.floor(Math.min(startZ, endZ) - radius), 16);
    int maxChunkZ = Math.floorDiv((int) Math.floor(Math.max(startZ, endZ) + radius), 16);
    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
      for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
        if (cx == centerChunkX && cz == centerChunkZ) {
          continue; // the caller fetches the cell's own chunk itself, as a direct (urgent) fetch
        }
        if (touchesChunk(startX, startZ, endX, endZ, radius, cx, cz)) {
          visitor.visit(cx, cz);
        }
      }
    }
  }

  /**
   * Returns whether the column — every point within {@code radius} of the segment from ({@code
   * startX}, {@code startZ}) to ({@code endX}, {@code endZ}) — reaches into the given chunk's
   * square footprint.
   */
  private static boolean touchesChunk(
      double startX, double startZ, double endX, double endZ, double radius, int cx, int cz) {
    double minX = cx << 4;
    double minZ = cz << 4;
    double maxX = minX + 16;
    double maxZ = minZ + 16;
    if (segmentTouchesBox(startX, startZ, endX, endZ, minX, minZ, maxX, maxZ)) {
      return true;
    }
    // The segment misses the square, so the two are disjoint convex shapes and their nearest pair
    // of points involves a vertex of one of them: measuring both segment ends against the square
    // and all four corners against the segment covers every case.
    double radiusSquared = radius * radius;
    if (pointToBoxSquared(startX, startZ, minX, minZ, maxX, maxZ) <= radiusSquared
        || pointToBoxSquared(endX, endZ, minX, minZ, maxX, maxZ) <= radiusSquared) {
      return true;
    }
    return pointToSegmentSquared(minX, minZ, startX, startZ, endX, endZ) <= radiusSquared
        || pointToSegmentSquared(maxX, minZ, startX, startZ, endX, endZ) <= radiusSquared
        || pointToSegmentSquared(minX, maxZ, startX, startZ, endX, endZ) <= radiusSquared
        || pointToSegmentSquared(maxX, maxZ, startX, startZ, endX, endZ) <= radiusSquared;
  }

  /** Liang-Barsky: whether a segment enters an axis-aligned box at all. */
  private static boolean segmentTouchesBox(
      double startX,
      double startZ,
      double endX,
      double endZ,
      double minX,
      double minZ,
      double maxX,
      double maxZ) {
    double deltaX = endX - startX;
    double deltaZ = endZ - startZ;
    double[] edgeDirections = {-deltaX, deltaX, -deltaZ, deltaZ};
    double[] edgeDistances = {startX - minX, maxX - startX, startZ - minZ, maxZ - startZ};
    double enter = 0;
    double exit = 1;
    for (int i = 0; i < edgeDirections.length; i++) {
      double direction = edgeDirections[i];
      double distance = edgeDistances[i];
      if (direction == 0) {
        if (distance < 0) {
          return false; // parallel to this edge, and wholly outside it
        }
        continue;
      }
      double t = distance / direction;
      if (direction < 0) {
        enter = Math.max(enter, t);
      } else {
        exit = Math.min(exit, t);
      }
      if (enter > exit) {
        return false;
      }
    }
    return true;
  }

  /** The squared distance from a point to the nearest point of an axis-aligned box. */
  private static double pointToBoxSquared(
      double x, double z, double minX, double minZ, double maxX, double maxZ) {
    double dx = Math.max(0, Math.max(minX - x, x - maxX));
    double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
    return dx * dx + dz * dz;
  }

  /** The squared distance from a point to the nearest point of a segment. */
  private static double pointToSegmentSquared(
      double x, double z, double startX, double startZ, double endX, double endZ) {
    double deltaX = endX - startX;
    double deltaZ = endZ - startZ;
    double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
    double t = 0;
    if (lengthSquared > 0) {
      double projection = ((x - startX) * deltaX + (z - startZ) * deltaZ) / lengthSquared;
      t = Math.max(0, Math.min(1, projection));
    }
    double dx = x - (startX + t * deltaX);
    double dz = z - (startZ + t * deltaZ);
    return dx * dx + dz * dz;
  }
}
