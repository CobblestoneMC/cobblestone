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

  /** The region as a solve sees it, reading through {@code world}. */
  private static DomainRegion<MinecraftWorld> solving(SurfaceRegion region, MinecraftWorld world) {
    return region.scopedForSolve(world);
  }

  @Test
  void openColumnIsSurfaceAboveTheGround() {
    TestWorld world = world();
    DomainRegion<MinecraftWorld> surface = solving(SurfaceRegion.anywhere(world), world);
    assertTrue(surface.contains(new Cell(2, 65, 0)));
    assertTrue(surface.contains(new Cell(2, 100, 0)));
  }

  @Test
  void roofedColumnIsNotSurfaceUnderTheRoof() {
    TestWorld world = world();
    DomainRegion<MinecraftWorld> surface = solving(SurfaceRegion.anywhere(world), world);
    assertFalse(surface.contains(new Cell(-2, 65, 0)));
    assertTrue(surface.contains(new Cell(-2, 71, 0)));
  }

  @Test
  void nearestBoundaryIsStraightUpAboveTheRoof() {
    TestWorld world = world();
    DomainRegion<MinecraftWorld> surface = solving(SurfaceRegion.anywhere(world), world);
    assertEquals(new Cell(-2, 71, 0), surface.nearestBoundaryCell(new Cell(-2, 65, 0)));
  }

  @Test
  void nearestBoundaryOfAnOpenCellIsItself() {
    TestWorld world = world();
    DomainRegion<MinecraftWorld> surface = solving(SurfaceRegion.anywhere(world), world);
    Cell open = new Cell(2, 65, 0);
    assertEquals(open, surface.nearestBoundaryCell(open));
  }

  @Test
  void columnRegionOnlyContainsItsColumn() {
    TestWorld world = world();
    DomainRegion<MinecraftWorld> column = solving(SurfaceRegion.column(world, 3, 3), world);
    assertTrue(column.contains(new Cell(3, 65, 3)));
    assertFalse(column.contains(new Cell(2, 65, 3)));
  }

  @Test
  void columnRegionSteersAcrossWithoutReadingTheColumn() {
    CountingWorld world = new CountingWorld(world());
    DomainRegion<MinecraftWorld> column = solving(SurfaceRegion.column(world, -3, 3), world);
    // Level with the player until they reach it...
    assertEquals(new Cell(-3, 40, 3), column.nearestBoundaryCell(new Cell(2, 40, 0)));
    assertEquals(0, world.reads.get());
    // ...then up through its roof.
    assertEquals(new Cell(-3, 71, 3), column.nearestBoundaryCell(new Cell(-3, 65, 3)));
  }

  @Test
  void emptyColumnIsSurfaceAllTheWayDown() {
    TestWorld empty = TestWorld.builder("minecraft:the_end").build();
    assertTrue(
        solving(SurfaceRegion.anywhere(empty), empty).contains(new Cell(0, empty.minY(), 0)));
  }

  @Test
  void anUnboundRegionReadsNothingAndClaimsNothing() {
    CountingWorld world = new CountingWorld(world());
    SurfaceRegion surface = SurfaceRegion.anywhere(world);
    Cell open = new Cell(2, 65, 0);
    assertFalse(surface.contains(open));
    assertEquals(open, surface.nearestBoundaryCell(open));
    assertEquals(0, world.reads.get());
  }

  @Test
  void aChunkNotYetInHandIsNotWaitedFor() {
    TestWorld world = world();
    DomainRegion<MinecraftWorld> surface =
        solving(SurfaceRegion.anywhere(world), new LoadingWorld(world));
    Cell underRoof = new Cell(-2, 65, 0);
    assertFalse(surface.contains(new Cell(2, 65, 0)), "unknown is not open");
    assertEquals(underRoof, surface.nearestBoundaryCell(underRoof), "no guess about the roof");
  }

  @Test
  void scopedRegionKeepsTheOriginalDomain() {
    TestWorld world = world();
    SurfaceRegion surface = SurfaceRegion.anywhere(world);
    assertEquals(world, surface.scopedForSolve(world).domain());
  }

  /** A world that counts the chunks read through it. */
  private static final class CountingWorld extends ForwardingWorld {
    private final AtomicInteger reads = new AtomicInteger();

    CountingWorld(MinecraftWorld delegate) {
      super(delegate);
    }

    @Override
    public FutureOr<MinecraftChunk> chunkAt(Cell cell, Cell destination) {
      reads.incrementAndGet();
      return super.chunkAt(cell, destination);
    }
  }

  /** A world whose chunks are all still loading. */
  private static final class LoadingWorld extends ForwardingWorld {

    LoadingWorld(MinecraftWorld delegate) {
      super(delegate);
    }

    @Override
    public FutureOr<MinecraftChunk> chunkAt(Cell cell, Cell destination) {
      return FutureOr.ofFuture(new CompletableFuture<>());
    }
  }

  private abstract static class ForwardingWorld implements MinecraftWorld {
    private final MinecraftWorld delegate;

    ForwardingWorld(MinecraftWorld delegate) {
      this.delegate = delegate;
    }

    @Override
    public String key() {
      return delegate.key();
    }

    @Override
    public Environment environment() {
      return delegate.environment();
    }

    @Override
    public int minY() {
      return delegate.minY();
    }

    @Override
    public int maxY() {
      return delegate.maxY();
    }

    @Override
    public FutureOr<MinecraftBlock> blockAt(Cell cell, Cell destination) {
      return delegate.blockAt(cell, destination);
    }

    @Override
    public FutureOr<MinecraftChunk> chunkAt(Cell cell, Cell destination) {
      return delegate.chunkAt(cell, destination);
    }
  }
}
