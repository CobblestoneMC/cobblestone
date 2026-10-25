/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFilesTest {

  private final List<RegionStore<ToyWorld.Column>> opened = new ArrayList<>();
  private final List<RegionFiles> openedFiles = new ArrayList<>();

  private RegionStore<ToyWorld.Column> store(Path root) {
    return store(root, ToyWorld.spec(), Long.MAX_VALUE);
  }

  private RegionStore<ToyWorld.Column> store(
      Path root, RegionSpec<ToyWorld.Column> spec, long memoryBudget) {
    RegionStore<ToyWorld.Column> store =
        new RegionStore<>(spec, memoryBudget, root, Runnable::run, Runnable::run);
    opened.add(store);
    return store;
  }

  private RegionFiles files(Path root) {
    RegionFiles files = new RegionFiles(root, ToyWorld.spec());
    openedFiles.add(files);
    return files;
  }

  /** Releases every open file, so the temporary directory can be deleted. */
  @AfterEach
  void close() throws Exception {
    opened.forEach(store -> store.close().join());
    for (RegionFiles files : openedFiles) {
      files.close();
    }
  }

  private static RegionColumn column(
      RegionStore<ToyWorld.Column> store, int cx, int cz, ToyWorld.Source source) {
    FutureOr<RegionColumn> column = store.column(cx, cz, source);
    assertTrue(column.isImmediate(), "every step here answers at once");
    return column.value();
  }

  private static RegionColumn build(ToyWorld.Blocks blocks, int cx, int cz) {
    ToyWorld.Column terrain = new ToyWorld.Source(blocks, 16).column(cx, cz).value();
    return new RegionColumnBuilder<>(ToyWorld.spec()).build(cx, cz, terrain);
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

  private static Path onlyFile(Path root) throws Exception {
    try (var files = Files.list(root)) {
      List<Path> all = files.toList();
      assertEquals(1, all.size());
      return all.get(0);
    }
  }

  @Test
  void aColumnReadBackFromDiskIsTheColumnThatWasBuilt(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> first = store(root);
    RegionColumn built = column(first, 3, -2, source);
    assertTrue(built.regions() > 3, "the toy terrain gives a column several regions");
    first.flush().join();

    // A new store over the same directory: a restart.
    RegionStore<ToyWorld.Column> restarted = store(root);
    RegionColumn read = column(restarted, 3, -2, source);

    assertEquals(1, source.reads, "the restarted store reads the disk, not the terrain");
    assertEquals(1, restarted.stats().diskLoads());
    assertEquals(0, restarted.stats().builds());
    assertSame(built, read);
  }

  @Test
  void aCrowdedSectionReadsBackWhole(@TempDir Path root) throws Exception {
    ToyWorld.Blocks checkerboard =
        (x, y, z) ->
            y < 16
                ? ToyWorld.Material.STONE
                : y < 32 && ((x + y + z) & 1) == 0
                    ? ToyWorld.Material.STONE
                    : ToyWorld.Material.AIR;
    RegionColumn built = build(checkerboard, 0, 0);
    assertNotNull(built.wideCells[1]);

    files(root).write(List.of(built), List.of());
    assertSame(built, files(root).read(0, 0));
  }

  @Test
  void aSearchOverStoredColumnsGivesTheSameAnswerAfterARestart(@TempDir Path root) {
    Cell goal = new Cell(0, 13, 0);
    Cell from = new Cell(150, 13, 40);
    RegionAgent walker = new ToyWorld.Walker();

    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> first = store(root);
    double before =
        new RegionSearch(new RegionGraph<>(first, source), walker, from, goal::equals, cell -> goal)
            .costToGoal(from);
    first.flush().join();
    int readsBefore = source.reads;

    RegionStore<ToyWorld.Column> restarted = store(root);
    double after =
        new RegionSearch(
                new RegionGraph<>(restarted, source), walker, from, goal::equals, cell -> goal)
            .costToGoal(from);

    assertEquals(before, after, 1e-9);
    assertEquals(readsBefore, source.reads, "nothing is built again after a restart");
  }

  @Test
  void anInvalidatedColumnIsBuiltAgainAndForgottenOnDisk(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> store = store(root);
    column(store, 0, 0, source);
    store.flush().join();

    store.invalidate(0, 0);
    store.flush().join();
    column(store, 0, 0, source);

    assertEquals(2, source.reads, "a changed column is built again");
    RegionStore<ToyWorld.Column> restarted = store(root);
    column(restarted, 0, 0, source);
    // The rebuilt column was not flushed, and the stale one was removed, so a restart rebuilds.
    assertEquals(3, source.reads);
  }

  @Test
  void anInvalidatedColumnIsNotReadBackFromDiskBeforeTheNextFlush(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> store = store(root);
    column(store, 0, 0, source);
    store.flush().join();

    // Dropped from memory by a block change, still on disk until the next flush: the copy there is
    // the terrain before the change, and must not come back.
    store.invalidate(0, 0);
    column(store, 0, 0, source);

    assertEquals(2, source.reads, "a changed column is built again, not read back stale");
    assertEquals(0, store.stats().diskLoads());
  }

  @Test
  void aColumnWhoseTerrainCannotBeReadIsNeverWritten(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    source.unreadable = true;
    RegionStore<ToyWorld.Column> store = store(root);
    assertEquals(0, column(store, 5, 5, source).regions());
    store.flush().join();

    source.unreadable = false;
    RegionStore<ToyWorld.Column> restarted = store(root);
    // Generated since: a store that had saved it empty would wall it off for good.
    assertTrue(column(restarted, 5, 5, source).regions() > 0);
  }

  @Test
  void filesWrittenUnderOtherRulesAreIgnoredAndStartedAfresh(@TempDir Path root) throws Exception {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> first = store(root);
    column(first, 1, 1, source);
    column(first, 2, 1, source);
    first.flush().join();
    first.close().join();

    // The same files, read as though the fill's detour ratio had changed, and then the terrain's
    // rules.
    RegionSpec<ToyWorld.Column> otherDetour =
        new RegionSpec<>(ToyWorld.TERRAIN, ToyWorld.MIN_Y, ToyWorld.MAX_Y, 2.0);
    RegionSpec<ToyWorld.Column> otherRules =
        new RegionSpec<>(new ToyWorld.Terrain(2, 4), ToyWorld.MIN_Y, ToyWorld.MAX_Y);
    for (RegionSpec<ToyWorld.Column> spec : List.of(otherDetour, otherRules)) {
      RegionStore<ToyWorld.Column> other = store(root, spec, Long.MAX_VALUE);
      column(other, 1, 1, source);
      assertEquals(0, other.stats().diskLoads());
      assertEquals(1, other.stats().builds(), "built again, not read under the wrong rules");
      other.flush().join();
      other.close().join();
    }

    // The last write started the file afresh under its own rules: column (2, 1), written under
    // the first, is gone rather than mixed in.
    RegionFiles underLast = new RegionFiles(root, otherRules);
    openedFiles.add(underLast);
    assertNotNull(underLast.read(1, 1));
    assertNull(underLast.read(2, 1));
    onlyFile(root);
  }

  @Test
  void aTornRecordReadsAsMissing(@TempDir Path root) throws Exception {
    RegionColumn built = build(ToyWorld::hills, 0, 0);
    RegionFiles files = files(root);
    files.write(List.of(built), List.of());
    files.close();

    // A crash partway through a record: a byte in its middle is not what was written.
    Path file = onlyFile(root);
    try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
      // The first record starts right after the header; its first 8 bytes are length and checksum.
      long middle = (long) RegionFiles.HEADER_SECTORS * RegionFiles.SECTOR + 100;
      handle.seek(middle);
      int was = handle.read();
      handle.seek(middle);
      handle.write(was ^ 0xFF);
    }
    assertNull(files(root).read(0, 0), "a torn record is a column to build again");
  }

  @Test
  void rewritingAColumnWritesOnlyItsRecordInPlace(@TempDir Path root) throws Exception {
    RegionFiles files = files(root);
    List<RegionColumn> many = new ArrayList<>();
    for (int cx = 0; cx < 8; cx++) {
      many.add(build(ToyWorld::hills, cx, 0));
    }
    files.write(many, List.of());
    long size = Files.size(onlyFile(root));
    long written = files.bytesWritten();

    // The same column again: its sectors are reused, the file does not grow, and only one record
    // is written, not the file.
    RegionColumn again = build(ToyWorld::hills, 3, 0);
    files.write(List.of(again), List.of());
    assertEquals(size, Files.size(onlyFile(root)));
    long once = files.bytesWritten() - written;
    assertTrue(once < size / 4, "one record, not the file: " + once + " of " + size);
    assertSame(again, files.read(3, 0));
  }

  @Test
  void aRemovedColumnsSectorsAreReused(@TempDir Path root) throws Exception {
    RegionFiles files = files(root);
    files.write(List.of(build(ToyWorld::hills, 0, 0), build(ToyWorld::hills, 1, 0)), List.of());
    long size = Files.size(onlyFile(root));

    files.write(List.of(), List.of(0L)); // column (0, 0)
    assertNull(files.read(0, 0));
    files.write(List.of(build(ToyWorld::hills, 0, 0)), List.of());
    assertEquals(size, Files.size(onlyFile(root)), "written back into the space it left");
    assertNotNull(files.read(1, 0));

    // And a restart sees the same layout.
    files.close();
    RegionFiles reopened = files(root);
    assertNotNull(reopened.read(0, 0));
    assertNotNull(reopened.read(1, 0));
  }

  @Test
  void aTightBudgetEvictsButNeverLosesAnUnwrittenColumn(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    // A byte: every column but the newest is evicted at once.
    RegionStore<ToyWorld.Column> tight = store(root, ToyWorld.spec(), 1);
    for (int cx = 0; cx < 4; cx++) {
      column(tight, cx, 0, source);
    }
    assertTrue(tight.stats().evictions() >= 3);
    tight.flush().join();

    RegionStore<ToyWorld.Column> restarted = store(root);
    for (int cx = 0; cx < 4; cx++) {
      column(restarted, cx, 0, source);
    }
    assertEquals(4, restarted.stats().diskLoads(), "every evicted column still reached the disk");
  }
}
