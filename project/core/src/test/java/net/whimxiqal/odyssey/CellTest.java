/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CellTest {

  @Test
  void accessorsAndEquality() {
    Cell a = new Cell(1, 2, 3);
    assertEquals(1, a.x());
    assertEquals(2, a.y());
    assertEquals(3, a.z());
    assertEquals(new Cell(1, 2, 3), a);
    assertEquals(new Cell(1, 2, 3).hashCode(), a.hashCode());
    assertNotEquals(new Cell(1, 2, 4), a);
  }

  @Test
  void plusOffsets() {
    assertEquals(new Cell(2, 0, -1), new Cell(1, 1, 1).plus(1, -1, -2));
  }

  @Test
  void euclideanDistance() {
    Cell origin = new Cell(0, 0, 0);
    assertEquals(5.0, origin.distance(new Cell(3, 4, 0)), 1e-9);
    assertEquals(25.0, origin.distanceSquared(new Cell(3, 4, 0)), 1e-9);
    assertEquals(0.0, origin.distance(origin), 1e-9);
  }

  @Test
  void distanceDoesNotOverflowForLargeCoordinates() {
    // 30M-block coordinates squared exceed int range; the double math must stay correct.
    Cell far = new Cell(30_000_000, 0, 0);
    assertEquals(9.0e14, new Cell(0, 0, 0).distanceSquared(far), 1e6);
  }

  @Test
  void manhattanDistance() {
    assertEquals(9, new Cell(0, 0, 0).manhattan(new Cell(2, -3, 4)));
    assertEquals(0, new Cell(5, 5, 5).manhattan(new Cell(5, 5, 5)));
  }
}
