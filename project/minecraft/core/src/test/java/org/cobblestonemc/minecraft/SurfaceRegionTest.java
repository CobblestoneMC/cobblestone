/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.Cell;
import org.junit.jupiter.api.Test;

class SurfaceRegionTest {

  /** Ground at y=64 everywhere in a 9×9 patch, with a stone roof at y=70 over x ≤ 0. */
  private static TestWorld world() {
    return TestWorld.builder("minecraft:overworld")
        .floor(64, -4, -4, 4, 4, TestBlocks.solid())
        .floor(70, -4, -4, 0, 4, TestBlocks.solid())
        .build();
  }

  @Test
  void openColumnIsSurfaceAboveTheGround() {
    SurfaceRegion surface = SurfaceRegion.anywhere(world());
    assertTrue(surface.contains(new Cell(2, 65, 0)));
    assertTrue(surface.contains(new Cell(2, 100, 0)));
  }

  @Test
  void roofedColumnIsNotSurfaceUnderTheRoof() {
    SurfaceRegion surface = SurfaceRegion.anywhere(world());
    assertFalse(surface.contains(new Cell(-2, 65, 0)));
    assertTrue(surface.contains(new Cell(-2, 71, 0)));
  }

  @Test
  void nearestBoundaryIsStraightUpAboveTheRoof() {
    SurfaceRegion surface = SurfaceRegion.anywhere(world());
    assertEquals(new Cell(-2, 71, 0), surface.nearestBoundaryCell(new Cell(-2, 65, 0)));
  }

  @Test
  void nearestBoundaryOfAnOpenCellIsItself() {
    SurfaceRegion surface = SurfaceRegion.anywhere(world());
    Cell open = new Cell(2, 65, 0);
    assertEquals(open, surface.nearestBoundaryCell(open));
  }

  @Test
  void columnRegionOnlyContainsItsColumn() {
    SurfaceRegion column = SurfaceRegion.column(world(), 3, 3);
    assertTrue(column.contains(new Cell(3, 65, 3)));
    assertFalse(column.contains(new Cell(2, 65, 3)));
    assertEquals(new Cell(3, 65, 3), column.nearestBoundaryCell(new Cell(-2, 65, 0)));
  }

  @Test
  void emptyColumnIsSurfaceAllTheWayDown() {
    TestWorld empty = TestWorld.builder("minecraft:the_end").build();
    assertTrue(SurfaceRegion.anywhere(empty).contains(new Cell(0, empty.minY(), 0)));
  }

  @Test
  void scopedRegionKeepsTheOriginalDomain() {
    TestWorld world = world();
    SurfaceRegion surface = SurfaceRegion.anywhere(world);
    assertEquals(world, surface.scopedForSolve(world).domain());
  }
}
