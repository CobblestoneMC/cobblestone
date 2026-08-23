/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.List;
import org.cobblestonemc.Cell;

/**
 * The six cardinal block directions, using Minecraft's axis conventions (north = -Z, south = +Z,
 * east = +X, west = -X, up = +Y, down = -Y).
 */
public enum Direction {
  NORTH(0, 0, -1),
  EAST(1, 0, 0),
  SOUTH(0, 0, 1),
  WEST(-1, 0, 0),
  UP(0, 1, 0),
  DOWN(0, -1, 0);

  /** The four horizontal directions, in clockwise order from north. */
  public static final List<Direction> HORIZONTAL = List.of(NORTH, EAST, SOUTH, WEST);

  private final int dx;
  private final int dy;
  private final int dz;

  Direction(int dx, int dy, int dz) {
    this.dx = dx;
    this.dy = dy;
    this.dz = dz;
  }

  /** Delta in x direction. */
  public int dx() {
    return dx;
  }

  /** Delta in y direction. */
  public int dy() {
    return dy;
  }

  /** Delta in z direction. */
  public int dz() {
    return dz;
  }

  /**
   * Returns whether this direction is horizontal (has no vertical component).
   *
   * @return {@code true} for north/east/south/west
   */
  public boolean isHorizontal() {
    return dy == 0;
  }

  /**
   * Returns the cell one step from {@code cell} in this direction.
   *
   * @param cell the origin cell
   * @return the offset cell
   */
  public Cell offset(Cell cell) {
    return cell.plus(dx, dy, dz);
  }

  /**
   * Returns the opposite direction.
   *
   * @return the opposite
   */
  public Direction opposite() {
    return switch (this) {
      case NORTH -> SOUTH;
      case SOUTH -> NORTH;
      case EAST -> WEST;
      case WEST -> EAST;
      case UP -> DOWN;
      case DOWN -> UP;
    };
  }
}
