/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BlockLookupTest {

  /**
   * Every cell an expansion reads must fit the view box, or the cells outside it would be silently
   * dropped and read back as unknown — which looks like solid rock to the movement rules, so the
   * failure would be a mysteriously impassable world rather than an error. Widen the box if this
   * fails.
   */
  @Test
  void everyReadFitsTheViewBox() {
    for (int[] offset : MinecraftMovementBehavior.allOffsets()) {
      assertTrue(
          Math.abs(offset[0]) <= BlockLookup.VIEW_XZ_RADIUS
              && Math.abs(offset[2]) <= BlockLookup.VIEW_XZ_RADIUS
              && offset[1] >= BlockLookup.VIEW_DY_LOW
              && offset[1] <= BlockLookup.VIEW_DY_HIGH,
          "%s is outside the view box".formatted(Arrays.toString(offset)));
    }
  }
}
