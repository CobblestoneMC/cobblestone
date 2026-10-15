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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.cobblestonemc.Cell;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.FutureOr;
import org.junit.jupiter.api.Test;

class SurfaceRegionTest {

  /** Ground at y=64 everywhere in a 9×9 patch, with a stone roof at y=70 over x ≤ 0. */
  private static TestWorld world() {
    return TestWorld.builder("minecraft:overworld")
        .floor(64, -4, -4, 4, 4, TestBlocks.solid())
        .floor(70, -4, -4, 0, 4, TestBlocks.solid())
        .build();
  }

  /** The region as a search sees it, reading the test world's blocks. */
  private static DomainRegion<MinecraftWorld> searching(RegionFactory region) {
    return region.create(TestWorld.CHUNKS);
  }

  @Test
  void openColumnIsSurfaceAboveTheGround() {
    DomainRegion<MinecraftWorld> surface = searching(SurfaceRegion.anywhere(world()));
    assertTrue(surface.contains(new Cell(2, 65, 0)));
    assertTrue(surface.contains(new Cell(2, 100, 0)));
  }

  @Test
  void roofedColumnIsNotSurfaceUnderTheRoof() {
    DomainRegion<MinecraftWorld> surface = searching(SurfaceRegion.anywhere(world()));
    assertFalse(surface.contains(new Cell(-2, 65, 0)));
    assertTrue(surface.contains(new Cell(-2, 71, 0)));
  }

  @Test
  void nearestBoundaryIsStraightUpAboveTheRoof() {
    DomainRegion<MinecraftWorld> surface = searching(SurfaceRegion.anywhere(world()));
    assertEquals(new Cell(-2, 71, 0), surface.nearestBoundaryCell(new Cell(-2, 65, 0)));
  }

  @Test
  void nearestBoundaryOfAnOpenCellIsItself() {
    DomainRegion<MinecraftWorld> surface = searching(SurfaceRegion.anywhere(world()));
    Cell open = new Cell(2, 65, 0);
    assertEquals(open, surface.nearestBoundaryCell(open));
  }

  @Test
  void columnRegionOnlyContainsItsColumn() {
    DomainRegion<MinecraftWorld> column = searching(SurfaceRegion.column(world(), 3, 3));
    assertTrue(column.contains(new Cell(3, 65, 3)));
    assertFalse(column.contains(new Cell(2, 65, 3)));
  }

  @Test
  void columnRegionSteersAcrossWithoutReadingTheColumn() {
    CountingChunks chunks = new CountingChunks();
    DomainRegion<MinecraftWorld> column = SurfaceRegion.column(world(), -3, 3).create(chunks);
    // Level with the player until they reach it...
    assertEquals(new Cell(-3, 40, 3), column.nearestBoundaryCell(new Cell(2, 40, 0)));
    assertEquals(0, chunks.reads.get());
    // ...then up through its roof.
    assertEquals(new Cell(-3, 71, 3), column.nearestBoundaryCell(new Cell(-3, 65, 3)));
  }

  @Test
  void emptyColumnIsSurfaceAllTheWayDown() {
    TestWorld empty = TestWorld.builder("minecraft:the_end").build();
    assertTrue(searching(SurfaceRegion.anywhere(empty)).contains(new Cell(0, empty.minY(), 0)));
  }

  @Test
  void aChunkNotYetInHandIsNotWaitedFor() {
    ChunkSource loading =
        (cell, world, destination) -> FutureOr.ofFuture(new CompletableFuture<>());
    DomainRegion<MinecraftWorld> surface = SurfaceRegion.anywhere(world()).create(loading);
    Cell underRoof = new Cell(-2, 65, 0);
    assertFalse(surface.contains(new Cell(2, 65, 0)), "unknown is not open");
    assertEquals(underRoof, surface.nearestBoundaryCell(underRoof), "no guess about the roof");
  }

  @Test
  void eachSearchReadsThroughItsOwnChunks() {
    RegionFactory factory = SurfaceRegion.anywhere(world());
    CountingChunks first = new CountingChunks();
    CountingChunks second = new CountingChunks();
    Cell open = new Cell(2, 65, 0);
    assertTrue(factory.create(first).contains(open));
    // A roof one search worked out is not carried into the next, which reads for itself.
    assertTrue(factory.create(second).contains(open));
    assertEquals(1, first.reads.get());
    assertEquals(1, second.reads.get());
  }

  @Test
  void theRegionIsInItsWorld() {
    TestWorld world = world();
    assertEquals(world, searching(SurfaceRegion.anywhere(world)).domain());
  }

  /** The test world's chunks, counting how many are read. */
  private static final class CountingChunks implements ChunkSource {
    private final AtomicInteger reads = new AtomicInteger();

    @Override
    public FutureOr<MinecraftChunk> chunk(Cell cell, MinecraftWorld world, Cell destination) {
      reads.incrementAndGet();
      return TestWorld.CHUNKS.chunk(cell, world, destination);
    }
  }
}
