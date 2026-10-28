/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * The cells a {@link Domain} holds: an inclusive range along each axis, or the whole {@code int}
 * range along an axis the domain does not bound.
 *
 * <p>Axes are numbered as {@link #X}, {@link #Y} and {@link #Z}, so code that treats them alike can
 * loop over them rather than name each.
 *
 * @param minX the least X, inclusive
 * @param maxX the greatest X, inclusive
 * @param minY the least Y, inclusive
 * @param maxY the greatest Y, inclusive
 * @param minZ the least Z, inclusive
 * @param maxZ the greatest Z, inclusive
 */
public record DomainBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {

  /** The X axis. */
  public static final int X = 0;

  /** The Y axis. */
  public static final int Y = 1;

  /** The Z axis. */
  public static final int Z = 2;

  /** Every cell there is: no axis bounded. */
  public static final DomainBounds UNBOUNDED =
      new DomainBounds(
          Integer.MIN_VALUE,
          Integer.MAX_VALUE,
          Integer.MIN_VALUE,
          Integer.MAX_VALUE,
          Integer.MIN_VALUE,
          Integer.MAX_VALUE);

  /**
   * Checks that each range is non-empty.
   *
   * @throws IllegalArgumentException if a range's least is past its greatest
   */
  public DomainBounds {
    if (minX > maxX || minY > maxY || minZ > maxZ) {
      throw new IllegalArgumentException("empty bounds: " + this);
    }
  }

  /**
   * Returns bounds along Y alone, as a world with a floor and a ceiling and no edges has.
   *
   * @param minY the least Y, inclusive
   * @param maxY the greatest Y, inclusive
   * @return the bounds
   */
  public static DomainBounds vertical(int minY, int maxY) {
    return new DomainBounds(
        Integer.MIN_VALUE, Integer.MAX_VALUE, minY, maxY, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }

  /**
   * Returns the least coordinate along an axis.
   *
   * @param axis {@link #X}, {@link #Y} or {@link #Z}
   * @return the least coordinate, inclusive
   */
  public int min(int axis) {
    return switch (axis) {
      case X -> minX;
      case Y -> minY;
      case Z -> minZ;
      default -> throw new IllegalArgumentException("no axis " + axis);
    };
  }

  /**
   * Returns the greatest coordinate along an axis.
   *
   * @param axis {@link #X}, {@link #Y} or {@link #Z}
   * @return the greatest coordinate, inclusive
   */
  public int max(int axis) {
    return switch (axis) {
      case X -> maxX;
      case Y -> maxY;
      case Z -> maxZ;
      default -> throw new IllegalArgumentException("no axis " + axis);
    };
  }

  /**
   * Returns whether the domain bounds an axis, rather than running the whole {@code int} range.
   *
   * @param axis {@link #X}, {@link #Y} or {@link #Z}
   * @return {@code true} if bounded
   */
  public boolean bounded(int axis) {
    return min(axis) != Integer.MIN_VALUE || max(axis) != Integer.MAX_VALUE;
  }

  /**
   * Returns whether a cell lies within the bounds.
   *
   * @param cell the cell
   * @return {@code true} if it does
   */
  public boolean contains(Cell cell) {
    return cell.x() >= minX
        && cell.x() <= maxX
        && cell.y() >= minY
        && cell.y() <= maxY
        && cell.z() >= minZ
        && cell.z() <= maxZ;
  }
}
