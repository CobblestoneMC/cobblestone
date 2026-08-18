/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import net.whimxiqal.odyssey.Cell;

/**
 * Shared coarse-body geometry helpers for the 1×1×1 movement model: a body needs two blocks of
 * vertical clearance and solid footing to stand.
 */
final class Geometry {

  private Geometry() {}

  /** Whether a body fits in {@code cell} — the cell and the cell above it are both passable. */
  static boolean bodyFits(BlockView view, Cell cell) {
    return bodyFits(view, cell, 2);
  }

  /**
   * Whether a {@code height}-tall body fits at {@code cell} (every cell in the column is passable).
   * A flying body can be modelled as 1 tall to slip through a 1-block hole (an end gateway).
   */
  static boolean bodyFits(BlockView view, Cell cell, int height) {
    for (int dy = 0; dy < height; dy++) {
      if (!view.at(cell, 0, dy, 0).isPassable()) {
        return false;
      }
    }
    return true;
  }

  /** Whether a body can stand in {@code cell}: it fits and the block below has a solid top. */
  static boolean standable(BlockView view, Cell cell) {
    return bodyFits(view, cell) && view.at(cell, 0, -1, 0).isSolidTop();
  }

  /**
   * Whether a diagonal move between two same-level cells is blocked by a solid corner (you may not
   * cut through a solid block diagonally). Both orthogonal corner cells must allow a body.
   */
  static boolean cornerBlocked(BlockView view, Cell from, int dx, int dz) {
    return !bodyFits(view, from.plus(dx, 0, 0)) || !bodyFits(view, from.plus(0, 0, dz));
  }

  /**
   * Whether a 3D diagonal move (as in free flight) is blocked by a solid corner. The two orthogonal
   * side columns the body squeezes past are checked at the start height and, when the move also
   * changes height, at the target height — so you cannot slip diagonally "up and through" a pair of
   * walls.
   */
  static boolean diagonalBlocked(BlockView view, Cell from, int dx, int dy, int dz) {
    if (!bodyFits(view, from.plus(dx, 0, 0)) || !bodyFits(view, from.plus(0, 0, dz))) {
      return true;
    }
    return dy != 0
        && (!bodyFits(view, from.plus(dx, dy, 0)) || !bodyFits(view, from.plus(0, dy, dz)));
  }
}
