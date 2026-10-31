/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.junit.jupiter.api.Test;

class RegionStoreTest {

  private static RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store(long memoryBudget) {
    return new RegionStore<>(
        ToyWorld.spec(), memoryBudget, RegionStorage.NONE, Runnable::run, Runnable::run);
  }

  private static RegionSection section(
      RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store, int sx, int sz, ToyWorld.Source source) {
    FutureOr<RegionSection> section = store.section(sx, 0, sz, source);
    assertTrue(section.isImmediate(), "every step here answers at once");
    return section.value();
  }

  @Test
  void aSectionIsBuiltOnceAndSharedBetweenSearches() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(Long.MAX_VALUE);
    Cell goal = new Cell(0, 13, 0);
    Cell from = new Cell(150, 13, 40);
    RegionAgent<ToyWorld.Toy> walker = new ToyWorld.Walker();

    double first =
        new RegionSearch<>(
                new RegionGraph<>(store, source), walker, from, goal::equals, cell -> goal)
            .costToGoal(from);
    int reads = source.reads;
    double second =
        new RegionSearch<>(
                new RegionGraph<>(store, source), walker, from, goal::equals, cell -> goal)
            .costToGoal(from);

    assertEquals(first, second, 1e-9);
    assertEquals(reads, source.reads, "the second search builds nothing");
    assertTrue(store.stats().memoryHits() > 0);
  }

  @Test
  void anInvalidatedSectionIsBuiltAgain() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(Long.MAX_VALUE);
    section(store, 0, 0, source);
    section(store, 0, 0, source);
    assertEquals(1, source.reads);

    store.invalidate(0, 0, 0);
    section(store, 0, 0, source);
    assertEquals(2, source.reads, "a changed section is built again");
  }

  @Test
  void aSectionChangedWhileItIsBuiltIsHandedOverButNotKept() {
    ToyWorld.Source source = new ToyWorld.Source();
    source.gate = new CompletableFuture<>();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(Long.MAX_VALUE);
    FutureOr<RegionSection> pending = store.section(0, 0, 0, source);
    assertFalse(pending.isImmediate());

    store.invalidate(0, 0, 0);
    source.gate.complete(null);

    assertTrue(pending.future().join().regions() > 0, "the asking search still gets it");
    assertFalse(store.inMemory(0, 0, 0), "but it describes the terrain before the change");
  }

  @Test
  void aSectionWhoseTerrainCannotBeReadIsNotKept() {
    ToyWorld.Source source = new ToyWorld.Source();
    source.unreadable = true;
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(Long.MAX_VALUE);
    assertEquals(0, section(store, 5, 5, source).regions());

    // Generated since: a store that had kept it empty would wall it off for good.
    source.unreadable = false;
    assertTrue(section(store, 5, 5, source).regions() > 0);
  }

  @Test
  void aSectionThatFailsToBuildDoesNotHoldUpTheNextAsk() {
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(Long.MAX_VALUE);
    ToyWorld.Source broken =
        new ToyWorld.Source(
            (x, y, z) -> {
              throw new IllegalStateException("a block nothing can classify");
            });
    assertTrue(store.section(0, 0, 0, broken).future().isCompletedExceptionally());

    assertTrue(section(store, 0, 0, new ToyWorld.Source()).regions() > 0, "asked again, it builds");
  }

  @Test
  void aTightBudgetEvictsTheLeastRecentlyUsed() {
    ToyWorld.Source source = new ToyWorld.Source();
    // A byte: every section but the newest is evicted at once.
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> tight = store(1);
    for (int cx = 0; cx < 4; cx++) {
      section(tight, cx, 0, source);
    }
    assertEquals(3, tight.stats().evictions());
    assertTrue(tight.inMemory(3, 0, 0), "never the section just asked for");
    assertFalse(tight.inMemory(0, 0, 0));
  }

  @Test
  void aPinnedSectionStaysWhateverTheBudgetUntilItsLastPinIsReleased() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> tight = store(1);
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    tight.pin(alice, 0, 0, 0, 0);
    tight.pin(bob, 0, 0, 0, 0);
    section(tight, 0, 0, source);
    for (int cx = 1; cx < 5; cx++) {
      section(tight, cx, 0, source);
    }
    assertTrue(tight.inMemory(0, 0, 0), "pinned, it stays");
    assertFalse(tight.inMemory(1, 0, 0), "the others go past the budget");
    assertEquals(1, tight.stats().pinnedSections());

    tight.release(alice);
    assertTrue(tight.inMemory(0, 0, 0), "still pinned for bob");
    tight.release(bob);
    assertFalse(tight.inMemory(0, 0, 0), "released, it goes too");
  }

  @Test
  void pinningAgainMovesAnOwnersPin() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> tight = store(1);
    UUID owner = UUID.randomUUID();
    tight.pin(owner, 0, 0, 0, 1);
    section(tight, 0, 0, source);
    section(tight, 1, 1, source);
    assertEquals(2, tight.stats().pinnedSections());

    // Walked on: the old surroundings are let go, the new ones held.
    tight.pin(owner, 10, 0, 0, 1);
    section(tight, 10, 0, source);
    assertFalse(tight.inMemory(0, 0, 0));
    assertFalse(tight.inMemory(1, 0, 1));
    assertTrue(tight.inMemory(10, 0, 0));
  }

  @Test
  void aPinnedSectionIsStillDroppedWhenItsTerrainChanges() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Blocks, ToyWorld.Toy> store = store(Long.MAX_VALUE);
    store.pin("trip", 0, 0, 0, 0);
    section(store, 0, 0, source);
    store.invalidate(0, 0, 0);
    assertFalse(store.inMemory(0, 0, 0));
    section(store, 0, 0, source);
    assertEquals(2, source.reads);
    assertEquals(1, store.stats().pinnedSections(), "rebuilt, it is pinned again");
  }
}
