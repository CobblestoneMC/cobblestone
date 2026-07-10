/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import java.util.HashSet;
import java.util.Set;
import net.whimxiqal.odyssey.api.Cell;

/** Helpers for enumerating the block cells a mode needs to inspect. */
final class Neighborhood {

  private Neighborhood() {
  }

  /**
   * Returns every cell within {@code xzRadius} horizontally and {@code [yLow, yHigh]} vertically of
   * {@code center} (inclusive). Slightly over-fetching is intentional — it keeps modes simple and
   * warms the chunk cache for adjacent expansions.
   */
  static Set<Cell> box(Cell center, int xzRadius, int dyLow, int dyHigh) {
    Set<Cell> cells = new HashSet<>();
    for (int dx = -xzRadius; dx <= xzRadius; dx++) {
      for (int dz = -xzRadius; dz <= xzRadius; dz++) {
        for (int dy = dyLow; dy <= dyHigh; dy++) {
          cells.add(center.plus(dx, dy, dz));
        }
      }
    }
    return cells;
  }
}
