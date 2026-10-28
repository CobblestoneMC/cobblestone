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
import org.cobblestonemc.DomainBounds;
import org.cobblestonemc.FutureOr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFilesTest {

  private final List<RegionStore<ToyWorld.Blocks, ToyWorld.Toy>> opened = new ArrayList<>();
  private final List<RegionFiles> openedFiles = new ArrayList<>();

  private RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store(Path root) {
    return store(root, ToyWorld.spec(), Long.MAX_VALUE);
  }

  private RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store(
      Path root, RegionSpec<ToyWorld.Blocks, ToyWorld.Toy> spec, long memoryBudget) {
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store =
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

  /** The section Y holding the toy hills' ground, so a section there has several regions. */
  private static final int GROUND = 0;

  private static RegionSection section(
      RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store, int sx, int sz, ToyWorld.Source source) {
    FutureOr<RegionSection> section = store.section(sx, GROUND, sz, source);
    assertTrue(section.isImmediate(), "every step here answers at once");
    return section.value();
  }

  private static RegionSection build(ToyWorld.Blocks blocks, int sx, int sy, int sz) {
    return new RegionSectionBuilder<>(ToyWorld.spec()).build(sx, sy, sz, blocks);
  }

  private static void assertSame(RegionSection expected, RegionSection actual) {
    assertArrayEquals(expected.regionClass, actual.regionClass);
    assertArrayEquals(expected.regionBox, actual.regionBox);
    assertArrayEquals(expected.portalA, actual.portalA);
    assertArrayEquals(expected.portalB, actual.portalB);
    assertArrayEquals(expected.portalFall, actual.portalFall);
    assertArrayEquals(expected.portalBox, actual.portalBox);
    assertArrayEquals(expected.exits, actual.exits);
    for (int cell = 0; cell < 16 * 16 * 16; cell++) {
      assertEquals(expected.regionAt(cell), actual.regionAt(cell));
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
  void aSectionReadBackFromDiskIsTheSectionThatWasBuilt(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> first = store(root);
    RegionSection built = section(first, 3, -2, source);
    assertTrue(built.regions() > 3, "the toy terrain gives a section several regions");
    first.flush().join();

    // A new store over the same directory: a restart.
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> restarted = store(root);
    RegionSection read = section(restarted, 3, -2, source);

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
    RegionSection built = build(checkerboard, 0, 1, 0);
    assertNotNull(built.wideCells);

    files(root).write(List.of(built), List.of());
    assertSame(built, files(root).read(0, 1, 0));
  }

  @Test
  void aWholeSectionIsKeptInItsSlotAlone(@TempDir Path root) throws Exception {
    // Solid stone, and open sky over the hills: one region each, and nothing to join.
    RegionSection rock = build((x, y, z) -> ToyWorld.Material.STONE, 0, 0, 0);
    RegionSection sky = build(ToyWorld::hills, 0, 2, 0);
    assertTrue(rock.uniform() && sky.uniform());

    RegionFiles files = files(root);
    files.write(List.of(rock, sky), List.of());
    assertEquals(
        (long) RegionFiles.HEADER_SECTORS * RegionFiles.SECTOR,
        Files.size(onlyFile(root)),
        "no record, no sector");
    files.close();

    RegionFiles reopened = files(root);
    assertSame(rock, reopened.read(0, 0, 0));
    assertSame(sky, reopened.read(0, 2, 0));
    assertEquals(0, reopened.recordsRead(), "read back without reading a record");
  }

  @Test
  void aSearchOverStoredSectionsGivesTheSameAnswerAfterARestart(@TempDir Path root) {
    Cell goal = new Cell(0, 13, 0);
    Cell from = new Cell(150, 13, 40);
    RegionAgent<ToyWorld.Toy> walker = new ToyWorld.Walker();

    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> first = store(root);
    double before =
        new RegionSearch<>(
                new RegionGraph<>(first, source), walker, from, goal::equals, cell -> goal)
            .costToGoal(from);
    first.flush().join();
    int readsBefore = source.reads;

    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> restarted = store(root);
    double after =
        new RegionSearch<>(
                new RegionGraph<>(restarted, source), walker, from, goal::equals, cell -> goal)
            .costToGoal(from);

    assertEquals(before, after, 1e-9);
    assertEquals(readsBefore, source.reads, "nothing is built again after a restart");
  }

  @Test
  void anInvalidatedSectionIsBuiltAgainAndForgottenOnDisk(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(root);
    section(store, 0, 0, source);
    store.flush().join();

    store.invalidate(0, GROUND, 0);
    store.flush().join();
    section(store, 0, 0, source);

    assertEquals(2, source.reads, "a changed section is built again");
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> restarted = store(root);
    section(restarted, 0, 0, source);
    // The rebuilt section was not flushed, and the stale one was removed, so a restart rebuilds.
    assertEquals(3, source.reads);
  }

  @Test
  void anInvalidatedSectionIsNotReadBackFromDiskBeforeTheNextFlush(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(root);
    section(store, 0, 0, source);
    store.flush().join();

    // Dropped from memory by a block change, still on disk until the next flush: the copy there is
    // the terrain before the change, and must not come back.
    store.invalidate(0, GROUND, 0);
    section(store, 0, 0, source);

    assertEquals(2, source.reads, "a changed section is built again, not read back stale");
    assertEquals(0, store.stats().diskLoads());
  }

  @Test
  void aSectionWhoseTerrainCannotBeReadIsNeverWritten(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    source.unreadable = true;
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(root);
    assertEquals(0, section(store, 5, 5, source).regions());
    store.flush().join();

    source.unreadable = false;
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> restarted = store(root);
    // Generated since: a store that had saved it empty would wall it off for good.
    assertTrue(section(restarted, 5, 5, source).regions() > 0);
  }

  @Test
  void filesWrittenUnderOtherRulesAreIgnoredAndStartedAfresh(@TempDir Path root) throws Exception {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> first = store(root);
    section(first, 1, 1, source);
    section(first, 2, 1, source);
    first.flush().join();
    first.close().join();

    // The same files, read as though the fill's detour ratio had changed, and then the terrain's
    // rules.
    RegionSpec<ToyWorld.Blocks, ToyWorld.Toy> otherDetour =
        new RegionSpec<>(
            ToyWorld.TERRAIN, DomainBounds.vertical(ToyWorld.MIN_Y, ToyWorld.MAX_Y), 2.0);
    RegionSpec<ToyWorld.Blocks, ToyWorld.Toy> otherRules =
        new RegionSpec<>(
            new ToyWorld.Terrain(2, 4), DomainBounds.vertical(ToyWorld.MIN_Y, ToyWorld.MAX_Y));
    for (RegionSpec<ToyWorld.Blocks, ToyWorld.Toy> spec : List.of(otherDetour, otherRules)) {
      RegionStore<ToyWorld.Blocks, ToyWorld.Toy> other = store(root, spec, Long.MAX_VALUE);
      section(other, 1, 1, source);
      assertEquals(0, other.stats().diskLoads());
      assertEquals(1, other.stats().builds(), "built again, not read under the wrong rules");
      other.flush().join();
      other.close().join();
    }

    // The last write started the file afresh under its own rules: section (2, 0, 1), written under
    // the first, is gone rather than mixed in.
    RegionFiles underLast = new RegionFiles(root, otherRules);
    openedFiles.add(underLast);
    assertNotNull(underLast.read(1, GROUND, 1));
    assertNull(underLast.read(2, GROUND, 1));
    onlyFile(root);
  }

  @Test
  void aTornRecordReadsAsMissing(@TempDir Path root) throws Exception {
    RegionSection built = build(ToyWorld::hills, 0, GROUND, 0);
    RegionFiles files = files(root);
    files.write(List.of(built), List.of());
    files.close();

    // A crash partway through a record: a byte in its middle is not what was written.
    Path file = onlyFile(root);
    try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "rw")) {
      // The first record starts right after the header; its first 8 bytes are length and checksum.
      long middle = (long) RegionFiles.HEADER_SECTORS * RegionFiles.SECTOR + 50;
      handle.seek(middle);
      int was = handle.read();
      handle.seek(middle);
      handle.write(was ^ 0xFF);
    }
    assertNull(files(root).read(0, GROUND, 0), "a torn record is a section to build again");
  }

  @Test
  void rewritingASectionWritesOnlyItsRecordInPlace(@TempDir Path root) throws Exception {
    RegionFiles files = files(root);
    List<RegionSection> many = new ArrayList<>();
    for (int sx = 0; sx < 8; sx++) {
      many.add(build(ToyWorld::hills, sx, GROUND, 0));
    }
    files.write(many, List.of());
    long size = Files.size(onlyFile(root));
    long written = files.bytesWritten();

    // The same section again: its sectors are reused, the file does not grow, and only one record
    // is written, not the file.
    RegionSection again = build(ToyWorld::hills, 3, GROUND, 0);
    files.write(List.of(again), List.of());
    assertEquals(size, Files.size(onlyFile(root)));
    long once = files.bytesWritten() - written;
    assertTrue(once < size / 4, "one record, not the file: " + once + " of " + size);
    assertSame(again, files.read(3, GROUND, 0));
  }

  @Test
  void aRemovedSectionsSectorsAreReused(@TempDir Path root) throws Exception {
    RegionFiles files = files(root);
    files.write(
        List.of(build(ToyWorld::hills, 0, GROUND, 0), build(ToyWorld::hills, 1, GROUND, 0)),
        List.of());
    long size = Files.size(onlyFile(root));

    files.write(List.of(), List.of(RegionSection.key(0, GROUND, 0)));
    assertNull(files.read(0, GROUND, 0));
    files.write(List.of(build(ToyWorld::hills, 0, GROUND, 0)), List.of());
    assertEquals(size, Files.size(onlyFile(root)), "written back into the space it left");
    assertNotNull(files.read(1, GROUND, 0));

    // And a restart sees the same layout.
    files.close();
    RegionFiles reopened = files(root);
    assertNotNull(reopened.read(0, GROUND, 0));
    assertNotNull(reopened.read(1, GROUND, 0));
  }

  @Test
  void aTightBudgetEvictsButNeverLosesAnUnwrittenSection(@TempDir Path root) {
    ToyWorld.Source source = new ToyWorld.Source();
    // A byte: every section but the newest is evicted at once.
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> tight = store(root, ToyWorld.spec(), 1);
    for (int cx = 0; cx < 4; cx++) {
      section(tight, cx, 0, source);
    }
    assertTrue(tight.stats().evictions() >= 3);
    tight.flush().join();

    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> restarted = store(root);
    for (int cx = 0; cx < 4; cx++) {
      section(restarted, cx, 0, source);
    }
    assertEquals(4, restarted.stats().diskLoads(), "every evicted section still reached the disk");
  }
}
