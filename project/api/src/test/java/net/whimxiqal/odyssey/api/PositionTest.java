/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PositionTest {

  @Test
  void accessors() {
    TestDomain overworld = TestDomain.of("overworld");
    Position<TestDomain> pos = new Position<>(new Cell(1, 2, 3), overworld);
    assertEquals(new Cell(1, 2, 3), pos.cell());
    assertEquals(overworld, pos.domain());
  }

  @Test
  void equalityUsesCellAndDomain() {
    TestDomain overworld = TestDomain.of("overworld");
    TestDomain nether = TestDomain.of("nether");
    Position<TestDomain> a = new Position<>(new Cell(0, 64, 0), overworld);

    assertEquals(new Position<>(new Cell(0, 64, 0), TestDomain.of("overworld")), a);
    assertEquals(a.hashCode(), new Position<>(new Cell(0, 64, 0), overworld).hashCode());
    assertNotEquals(new Position<>(new Cell(0, 64, 0), nether), a);
    assertNotEquals(new Position<>(new Cell(0, 65, 0), overworld), a);
  }
}
