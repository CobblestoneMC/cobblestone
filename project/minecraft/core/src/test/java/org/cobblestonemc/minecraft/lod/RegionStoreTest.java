/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionStoreTest {

  private record TestBlock(boolean passable, boolean solidTop, boolean water, double breakTime)
      implements MinecraftBlock {
    @Override
    public boolean isPassable() {
      return passable;
    }

    @Override
    public boolean isSolidTop() {
      return solidTop;
    }

    @Override
    public boolean isWater() {
      return water;
    }

    @Override
    public double breakTimeSeconds() {
      return breakTime;
    }
  }

  private static final MinecraftBlock AIR = new TestBlock(true, false, false, 0.0);
  private static final MinecraftBlock STONE = new TestBlock(false, true, false, 1.5);
  private static final MinecraftBlock WATER = new TestBlock(false, false, true, 0.0);

  private static final int MIN_Y = 0;
  private static final int MAX_Y = 47;

  /** Hills, a pond and a cave, so a column has mixed sections, falls and several classes. */
  private static MinecraftBlock terrain(int x, int y, int z) {
    int ground = 10 + Math.floorMod(x / 5 + z / 7, 4);
    if (y > ground) {
      return y <= 11 && Math.floorMod(x, 13) < 4 && Math.floorMod(z, 11) < 4 ? WATER : AIR;
    }
    if (y > 3 && y < 6 && Math.floorMod(x + z, 9) < 3) {
      return AIR; // a cave
    }
    return STONE;
  }

  /** A chunk source that counts what it is asked for. */
  private static final class Chunks implements RegionGraph.ChunkColumns {
    int reads;
    boolean unreadable;

    @Override
    public FutureOr<MinecraftChunk> column(int chunkX, int chunkZ) {
      reads++;
      if (unreadable) {
        return FutureOr.of(null);
      }
      MinecraftChunk chunk = (x, y, z) -> terrain(chunkX * 16 + x, y, chunkZ * 16 + z);
      return FutureOr.of(chunk);
    }

    @Override
    public int minY() {
      return MIN_Y;
    }

    @Override
    public int maxY() {
      return MAX_Y;
    }
  }

  private RegionStore store(Path root) {
    RegionColumnBuilder shape =
        new RegionColumnBuilder(MIN_Y, MAX_Y, RegionGraph.DEFAULT_DETOUR_RATIO);
    RegionFiles files =
        new RegionFiles(root, shape.minY(), shape.sections(), RegionGraph.DEFAULT_DETOUR_RATIO);
    return track(
        new RegionStore(
            MIN_Y,
            MAX_Y,
            RegionGraph.DEFAULT_DETOUR_RATIO,
            Long.MAX_VALUE,
            files,
            Runnable::run,
            Runnable::run));
  }

  private final List<RegionStore> opened = new ArrayList<>();

  private RegionStore track(RegionStore store) {
    opened.add(store);
    return store;
  }

  /** Releases every store's open files, so the temporary directory can be deleted. */
  @AfterEach
  void close() {
    opened.forEach(store -> store.close().join());
  }

  private static RegionColumn column(RegionStore store, int cx, int cz, Chunks chunks) {
    FutureOr<RegionColumn> column = store.column(cx, cz, chunks);
    assertTrue(column.isImmediate(), "every step here answers at once");
    return column.value();
  }

  private static void assertSame(RegionColumn expected, RegionColumn actual) {
    assertArrayEquals(expected.regionClass, actual.regionClass);
    assertArrayEquals(expected.regionBox, actual.regionBox);
    assertArrayEquals(expected.regionBreak, actual.regionBreak);
    assertArrayEquals(expected.portalA, actual.portalA);
    assertArrayEquals(expected.portalB, actual.portalB);
    assertArrayEquals(expected.portalDirected, actual.portalDirected);
    assertArrayEquals(expected.portalBox, actual.portalBox);
    for (int ly = 0; ly < expected.uniform.length * 16; ly++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          assertEquals(expected.regionAt(x, ly, z), actual.regionAt(x, ly, z));
        }
      }
    }
  }

  @Test
  void aColumnReadBackFromDiskIsTheColumnThatWasBuilt(@TempDir Path root) {
    Chunks chunks = new Chunks();
    RegionStore first = store(root);
    RegionColumn built = column(first, 3, -2, chunks);
    first.flush().join();

    // A new store over the same directory: a restart.
    RegionStore restarted = store(root);
    RegionColumn read = column(restarted, 3, -2, chunks);

    assertEquals(1, chunks.reads, "the restarted store reads the disk, not the chunk");
    assertEquals(1, restarted.stats().diskLoads());
    assertEquals(0, restarted.stats().builds());
    assertSame(built, read);
  }

  @Test
  void aSearchOverStoredColumnsGivesTheSameAnswerAfterARestart(@TempDir Path root) {
    Cell goal = new Cell(0, 13, 0);
    Cell from = new Cell(150, 13, 40);
    RegionCost walker = RegionCost.forCapabilities(RegionCost.survival());

    Chunks chunks = new Chunks();
    RegionStore first = store(root);
    double before =
        new RegionSearch(new RegionGraph(first, chunks), walker, goal, from).costToGoal(from);
    first.flush().join();
    int readsBefore = chunks.reads;

    RegionStore restarted = store(root);
    double after =
        new RegionSearch(new RegionGraph(restarted, chunks), walker, goal, from).costToGoal(from);

    assertEquals(before, after, 1e-9);
    assertEquals(readsBefore, chunks.reads, "nothing is built again after a restart");
  }

  @Test
  void anInvalidatedColumnIsBuiltAgainAndForgottenOnDisk(@TempDir Path root) {
    Chunks chunks = new Chunks();
    RegionStore store = store(root);
    column(store, 0, 0, chunks);
    store.flush().join();

    store.invalidate(0, 0);
    store.flush().join();
    column(store, 0, 0, chunks);

    assertEquals(2, chunks.reads, "a changed column is built again");
    RegionStore restarted = store(root);
    column(restarted, 0, 0, chunks);
    // The rebuilt column was not flushed, and the stale one was removed, so a restart rebuilds.
    assertEquals(3, chunks.reads);
  }

  @Test
  void anInvalidatedColumnIsNotReadBackFromDiskBeforeTheNextFlush(@TempDir Path root) {
    Chunks chunks = new Chunks();
    RegionStore store = store(root);
    column(store, 0, 0, chunks);
    store.flush().join();

    // Dropped from memory by a block change, still on disk until the next flush: the copy there is
    // the terrain before the change, and must not come back.
    store.invalidate(0, 0);
    column(store, 0, 0, chunks);

    assertEquals(2, chunks.reads, "a changed column is built again, not read back stale");
    assertEquals(0, store.stats().diskLoads());
  }

  @Test
  void aColumnWhoseChunkCannotBeReadIsNeverWritten(@TempDir Path root) {
    Chunks chunks = new Chunks();
    chunks.unreadable = true;
    RegionStore store = store(root);
    assertEquals(0, column(store, 5, 5, chunks).regions());
    store.flush().join();

    chunks.unreadable = false;
    RegionStore restarted = store(root);
    // Generated since: a store that had saved it empty would wall it off for good.
    assertTrue(column(restarted, 5, 5, chunks).regions() > 0);
  }

  @Test
  void filesWrittenUnderOtherRulesAreIgnored(@TempDir Path root) throws Exception {
    Chunks chunks = new Chunks();
    RegionStore first = store(root);
    column(first, 1, 1, chunks);
    first.flush().join();

    // The same files, read as though the fill's detour ratio had changed.
    RegionColumnBuilder shape = new RegionColumnBuilder(MIN_Y, MAX_Y, 2.0);
    RegionStore other =
        track(
            new RegionStore(
                MIN_Y,
                MAX_Y,
                2.0,
                Long.MAX_VALUE,
                new RegionFiles(root, shape.minY(), shape.sections(), 2.0),
                Runnable::run,
                Runnable::run));
    column(other, 1, 1, chunks);

    assertEquals(0, other.stats().diskLoads());
    assertEquals(1, other.stats().builds(), "built again, not read under the wrong rules");
    try (var files = Files.list(root)) {
      assertEquals(1, files.count(), "one file for the 32x32 columns around the origin");
    }
  }

  @Test
  void aTightBudgetEvictsButNeverLosesAnUnwrittenColumn(@TempDir Path root) {
    Chunks chunks = new Chunks();
    RegionColumnBuilder shape =
        new RegionColumnBuilder(MIN_Y, MAX_Y, RegionGraph.DEFAULT_DETOUR_RATIO);
    RegionStore tight =
        track(
            new RegionStore(
                MIN_Y,
                MAX_Y,
                RegionGraph.DEFAULT_DETOUR_RATIO,
                1, // a byte: every column but the newest is evicted at once
                new RegionFiles(
                    root, shape.minY(), shape.sections(), RegionGraph.DEFAULT_DETOUR_RATIO),
                Runnable::run,
                Runnable::run));
    for (int cx = 0; cx < 4; cx++) {
      column(tight, cx, 0, chunks);
    }
    assertTrue(tight.stats().evictions() >= 3);
    tight.flush().join();

    RegionStore restarted = store(root);
    for (int cx = 0; cx < 4; cx++) {
      column(restarted, cx, 0, chunks);
    }
    assertEquals(4, restarted.stats().diskLoads(), "every evicted column still reached the disk");
  }

  @Test
  void aLoadedChunksColumnIsNeverEvictedUntilItUnloads() {
    Chunks chunks = new Chunks();
    RegionStore tight =
        track(
            new RegionStore(
                MIN_Y,
                MAX_Y,
                RegionGraph.DEFAULT_DETOUR_RATIO,
                1, // a byte: every column not loaded is evicted at once
                null,
                Runnable::run,
                Runnable::run));
    tight.chunkLoaded(0, 0);
    column(tight, 0, 0, chunks);
    for (int cx = 1; cx < 5; cx++) {
      column(tight, cx, 0, chunks);
    }
    assertTrue(tight.known(0, 0), "the loaded chunk's column stays");
    assertTrue(!tight.known(1, 0), "the others go past the budget");

    tight.chunkUnloaded(0, 0);
    assertTrue(!tight.known(0, 0), "unloaded, it goes too");
  }
}
