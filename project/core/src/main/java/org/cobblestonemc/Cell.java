/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * An immutable {@code (x, y, z)} integer coordinate — the atomic 1×1×1 unit of space.
 *
 * <p>A {@code Cell} carries no domain; cells are only meaningful within a known {@link Domain}
 * context. Value-based equality and hash code come from the record components.
 *
 * @param x x coordinate
 * @param y y coordinate
 * @param z z coordinate
 */
public record Cell(int x, int y, int z) {

  /**
   * Returns a new cell offset from this one by the given deltas.
   *
   * @param dx the x delta
   * @param dy the y delta
   * @param dz the z delta
   * @return the offset cell
   */
  public Cell plus(int dx, int dy, int dz) {
    return new Cell(x + dx, y + dy, z + dz);
  }

  /**
   * Returns the euclidean distance from this cell to {@code other}.
   *
   * @param other the other cell
   * @return the euclidean distance
   */
  public double distance(Cell other) {
    return Math.sqrt(distanceSquared(other));
  }

  /**
   * Returns the squared euclidean distance to {@code other}, avoiding the square root.
   *
   * @param other the other cell
   * @return the squared euclidean distance
   */
  public double distanceSquared(Cell other) {
    double dx = (double) x - other.x;
    double dy = (double) y - other.y;
    double dz = (double) z - other.z;
    return dx * dx + dy * dy + dz * dz;
  }

  /**
   * Returns the Manhattan (L1) distance to {@code other}.
   *
   * @param other the other cell
   * @return the Manhattan distance
   */
  public int manhattan(Cell other) {
    return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
  }
}
