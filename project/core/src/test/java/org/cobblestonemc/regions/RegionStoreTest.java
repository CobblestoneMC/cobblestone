/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionStoreTest {

  private static final int MIN_Y = 0;
  private static final int MAX_Y = 47;

  /** What a toy world is made of, cell by cell. */
  private enum Material {
    AIR,
    STONE,
    WATER
  }

  /** A toy world's column: its material at each cell, by local X and Z and world Y. */
  private interface Column {
    Material at(int x, int y, int z);
  }

  /**
   * A toy terrain with the shape of a game's: ground where air sits on stone, air above it, water,
   * and stone that is dug through.
   */
  private static final class Terrain implements RegionTerrain<Column> {
    static final int GROUND = 0;
    static final int OPEN = 1;
    static final int POOL = 2;
    static final int ROCK = 3;

    private final int version;

    Terrain(int version) {
      this.version = version;
    }

    @Override
    public int version() {
      return version;
    }

    @Override
    public int classes() {
      return 4;
    }

    @Override
    public void classify(
        Column column, int worldMinY, int worldMaxY, int columnMinY, byte[] classes) {
      for (int cell = 0; cell < classes.length; cell++) {
        int y = columnMinY + (cell >> 8);
        int x = cell & 15;
        int z = (cell >> 4) & 15;
        if (y < worldMinY || y > worldMaxY) {
          classes[cell] = -1;
          continue;
        }
        classes[cell] =
            (byte)
                switch (column.at(x, y, z)) {
                  case STONE -> ROCK;
                  case WATER -> POOL;
                  case AIR ->
                      y > worldMinY && column.at(x, y - 1, z) == Material.STONE ? GROUND : OPEN;
                };
      }
    }

    @Override
    public boolean standing(int klass) {
      return klass == GROUND;
    }

    @Override
    public boolean steppable(int klass) {
      return klass == GROUND || klass == POOL;
    }

    @Override
    public boolean open(int klass) {
      return klass == OPEN;
    }

    @Override
    public boolean landing(int klass, int depth) {
      return (klass == GROUND && depth >= 2 && depth <= 3) || klass == POOL;
    }

    @Override
    public int deepestFall() {
      return 32;
    }

    @Override
    public boolean convex(int klass) {
      return klass != ROCK;
    }
  }

  /** Walks ground, swims water, and digs stone. */
  private static final class Walker implements RegionAgent {
    @Override
    public boolean usable(int klass) {
      return klass != Terrain.OPEN;
    }

    @Override
    public double traverse(
        int klass, double fx, double fy, double fz, double tx, double ty, double tz) {
      double length =
          Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
      return switch (klass) {
        case Terrain.GROUND -> 0.2 * length;
        case Terrain.POOL -> 0.3 * length;
        default -> 2.8 * Math.max(1, length);
      };
    }

    @Override
    public double cheapest() {
      return 0.2;
    }

    @Override
    public double fallback() {
      return 2.8;
    }
  }

  private static final Terrain TERRAIN = new Terrain(1);

  /** Hills, a pond and a cave, so a column has mixed sections, falls and several classes. */
  private static Material material(int x, int y, int z) {
    int ground = 10 + Math.floorMod(x / 5 + z / 7, 4);
    if (y > ground) {
      return y <= 11 && Math.floorMod(x, 13) < 4 && Math.floorMod(z, 11) < 4
          ? Material.WATER
          : Material.AIR;
    }
    if (y > 3 && y < 6 && Math.floorMod(x + z, 9) < 3) {
      return Material.AIR; // a cave
    }
    return Material.STONE;
  }

  /** A column source that counts what it is asked for. */
  private static final class Chunks implements RegionGraph.ChunkColumns<Column> {
    int reads;
    boolean unreadable;

    @Override
    public FutureOr<Column> column(int chunkX, int chunkZ) {
      reads++;
      if (unreadable) {
        return FutureOr.of(null);
      }
      Column column = (x, y, z) -> material(chunkX * 16 + x, y, chunkZ * 16 + z);
      return FutureOr.of(column);
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

  private RegionStore<Column> store(Path root) {
    return store(root, TERRAIN, RegionGraph.DEFAULT_DETOUR_RATIO, Long.MAX_VALUE);
  }

  private RegionStore<Column> store(
      Path root, Terrain terrain, double detourRatio, long memoryBudget) {
    RegionFiles files =
        new RegionFiles(
            root,
            RegionColumnBuilder.columnMinY(MIN_Y),
            RegionColumnBuilder.sections(MIN_Y, MAX_Y),
            detourRatio,
            terrain);
    return track(
        new RegionStore<>(
            terrain, MIN_Y, MAX_Y, detourRatio, memoryBudget, files, Runnable::run, Runnable::run));
  }

  private final List<RegionStore<Column>> opened = new ArrayList<>();

  private RegionStore<Column> track(RegionStore<Column> store) {
    opened.add(store);
    return store;
  }

  /** Releases every store's open files, so the temporary directory can be deleted. */
  @AfterEach
  void close() {
    opened.forEach(store -> store.close().join());
  }

  private static RegionColumn column(RegionStore<Column> store, int cx, int cz, Chunks chunks) {
    FutureOr<RegionColumn> column = store.column(cx, cz, chunks);
    assertTrue(column.isImmediate(), "every step here answers at once");
    return column.value();
  }

  private static void assertSame(RegionColumn expected, RegionColumn actual) {
    assertArrayEquals(expected.regionClass, actual.regionClass);
    assertArrayEquals(expected.regionBox, actual.regionBox);
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
    RegionStore<Column> first = store(root);
    RegionColumn built = column(first, 3, -2, chunks);
    assertTrue(built.regions() > 3, "the toy terrain gives a column several regions");
    first.flush().join();

    // A new store over the same directory: a restart.
    RegionStore<Column> restarted = store(root);
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
    RegionAgent walker = new Walker();

    Chunks chunks = new Chunks();
    RegionStore<Column> first = store(root);
    double before =
        new RegionSearch(new RegionGraph<>(first, chunks), walker, goal, from).costToGoal(from);
    first.flush().join();
    int readsBefore = chunks.reads;

    RegionStore<Column> restarted = store(root);
    double after =
        new RegionSearch(new RegionGraph<>(restarted, chunks), walker, goal, from).costToGoal(from);

    assertEquals(before, after, 1e-9);
    assertEquals(readsBefore, chunks.reads, "nothing is built again after a restart");
  }

  @Test
  void anInvalidatedColumnIsBuiltAgainAndForgottenOnDisk(@TempDir Path root) {
    Chunks chunks = new Chunks();
    RegionStore<Column> store = store(root);
    column(store, 0, 0, chunks);
    store.flush().join();

    store.invalidate(0, 0);
    store.flush().join();
    column(store, 0, 0, chunks);

    assertEquals(2, chunks.reads, "a changed column is built again");
    RegionStore<Column> restarted = store(root);
    column(restarted, 0, 0, chunks);
    // The rebuilt column was not flushed, and the stale one was removed, so a restart rebuilds.
    assertEquals(3, chunks.reads);
  }

  @Test
  void anInvalidatedColumnIsNotReadBackFromDiskBeforeTheNextFlush(@TempDir Path root) {
    Chunks chunks = new Chunks();
    RegionStore<Column> store = store(root);
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
    RegionStore<Column> store = store(root);
    assertEquals(0, column(store, 5, 5, chunks).regions());
    store.flush().join();

    chunks.unreadable = false;
    RegionStore<Column> restarted = store(root);
    // Generated since: a store that had saved it empty would wall it off for good.
    assertTrue(column(restarted, 5, 5, chunks).regions() > 0);
  }

  @Test
  void filesWrittenUnderOtherRulesAreIgnored(@TempDir Path root) throws Exception {
    Chunks chunks = new Chunks();
    RegionStore<Column> first = store(root);
    column(first, 1, 1, chunks);
    first.flush().join();

    // The same files, read as though the fill's detour ratio had changed, and then the terrain's
    // rules.
    RegionStore<Column> otherDetour = store(root, TERRAIN, 2.0, Long.MAX_VALUE);
    column(otherDetour, 1, 1, chunks);
    RegionStore<Column> otherRules =
        store(root, new Terrain(2), RegionGraph.DEFAULT_DETOUR_RATIO, Long.MAX_VALUE);
    column(otherRules, 1, 1, chunks);

    for (RegionStore<Column> other : List.of(otherDetour, otherRules)) {
      assertEquals(0, other.stats().diskLoads());
      assertEquals(1, other.stats().builds(), "built again, not read under the wrong rules");
    }
    try (var files = Files.list(root)) {
      assertEquals(1, files.count(), "one file for the 32x32 columns around the origin");
    }
  }

  @Test
  void aTightBudgetEvictsButNeverLosesAnUnwrittenColumn(@TempDir Path root) {
    Chunks chunks = new Chunks();
    // A byte: every column but the newest is evicted at once.
    RegionStore<Column> tight = store(root, TERRAIN, RegionGraph.DEFAULT_DETOUR_RATIO, 1);
    for (int cx = 0; cx < 4; cx++) {
      column(tight, cx, 0, chunks);
    }
    assertTrue(tight.stats().evictions() >= 3);
    tight.flush().join();

    RegionStore<Column> restarted = store(root);
    for (int cx = 0; cx < 4; cx++) {
      column(restarted, cx, 0, chunks);
    }
    assertEquals(4, restarted.stats().diskLoads(), "every evicted column still reached the disk");
  }

  @Test
  void aLoadedChunksColumnIsNeverEvictedUntilItUnloads() {
    Chunks chunks = new Chunks();
    RegionStore<Column> tight =
        track(
            new RegionStore<>(
                TERRAIN,
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
