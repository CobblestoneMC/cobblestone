/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.cobblestonemc.Cell;
import org.junit.jupiter.api.Test;

class ThreatsTest {

  @Test
  void aThreatFallsOffLinearlyToNothingPastItsRadius() {
    Threats threats = Threats.builder().mob(new Cell(0, 64, 0)).build();

    assertEquals(1.0, threats.mobs(new Cell(1, 64, 0), 16), 1e-9);
    assertEquals(9.0 / 16, threats.mobs(new Cell(8, 64, 0), 16), 1e-9);
    assertEquals(1.0 / 16, threats.mobs(new Cell(16, 64, 0), 16), 1e-9);
    assertEquals(0.0, threats.mobs(new Cell(17, 64, 0), 16), 1e-9);
  }

  @Test
  void theNearestThreatAcrossChunkBordersCounts() {
    // Either side of the border at x = 16, and far apart in the same chunk.
    Threats threats =
        Threats.builder().sculk(new Cell(15, 0, 0)).sculk(new Cell(31, 0, 31)).build();

    assertEquals(1.0, threats.sculk(new Cell(16, 0, 0), 8), 1e-9);
    assertEquals(0.0, threats.mobs(new Cell(16, 0, 0), 8), 1e-9, "sculk is not a mob");
  }

  @Test
  void anEmptySnapshotIsNone() {
    assertSame(Threats.NONE, Threats.builder().build());
  }
}
