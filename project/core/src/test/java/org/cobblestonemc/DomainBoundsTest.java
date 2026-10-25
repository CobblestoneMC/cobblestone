/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A domain's bounds, axis by axis. */
class DomainBoundsTest {

  @Test
  void aWorldWithAFloorAndACeilingBoundsOnlyY() {
    DomainBounds world = DomainBounds.vertical(-64, 319);
    assertFalse(world.bounded(DomainBounds.X));
    assertTrue(world.bounded(DomainBounds.Y));
    assertFalse(world.bounded(DomainBounds.Z));
    assertEquals(-64, world.min(DomainBounds.Y));
    assertEquals(319, world.max(DomainBounds.Y));
  }

  @Test
  void containsIsInclusiveOnEveryBoundedAxis() {
    DomainBounds box = new DomainBounds(0, 9, 0, 9, 0, 9);
    assertTrue(box.contains(new Cell(0, 9, 5)));
    assertFalse(box.contains(new Cell(10, 0, 0)));
    assertTrue(DomainBounds.UNBOUNDED.contains(new Cell(Integer.MIN_VALUE, 0, Integer.MAX_VALUE)));
  }

  @Test
  void emptyBoundsAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new DomainBounds(1, 0, 0, 0, 0, 0));
  }
}
