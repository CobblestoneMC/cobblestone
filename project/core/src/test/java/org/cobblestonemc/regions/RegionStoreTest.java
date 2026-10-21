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

  private static RegionStore<ToyWorld.Column> store(long memoryBudget) {
    return new RegionStore<>(ToyWorld.spec(), memoryBudget, null, Runnable::run, Runnable::run);
  }

  private static RegionColumn column(
      RegionStore<ToyWorld.Column> store, int cx, int cz, ToyWorld.Source source) {
    FutureOr<RegionColumn> column = store.column(cx, cz, source);
    assertTrue(column.isImmediate(), "every step here answers at once");
    return column.value();
  }

  @Test
  void aColumnIsBuiltOnceAndSharedBetweenSearches() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> store = store(Long.MAX_VALUE);
    Cell goal = new Cell(0, 13, 0);
    Cell from = new Cell(150, 13, 40);
    RegionAgent walker = new ToyWorld.Walker();

    double first =
        new RegionSearch(new RegionGraph<>(store, source), walker, goal, from, goal::equals)
            .costToGoal(from);
    int reads = source.reads;
    double second =
        new RegionSearch(new RegionGraph<>(store, source), walker, goal, from, goal::equals)
            .costToGoal(from);

    assertEquals(first, second, 1e-9);
    assertEquals(reads, source.reads, "the second search builds nothing");
    assertTrue(store.stats().memoryHits() > 0);
  }

  @Test
  void anInvalidatedColumnIsBuiltAgain() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> store = store(Long.MAX_VALUE);
    column(store, 0, 0, source);
    column(store, 0, 0, source);
    assertEquals(1, source.reads);

    store.invalidate(0, 0);
    column(store, 0, 0, source);
    assertEquals(2, source.reads, "a changed column is built again");
  }

  @Test
  void aColumnChangedWhileItIsBuiltIsHandedOverButNotKept() {
    ToyWorld.Source source = new ToyWorld.Source();
    source.gate = new CompletableFuture<>();
    RegionStore<ToyWorld.Column> store = store(Long.MAX_VALUE);
    FutureOr<RegionColumn> pending = store.column(0, 0, source);
    assertFalse(pending.isImmediate());

    store.invalidate(0, 0);
    source.gate.complete(null);

    assertTrue(pending.future().join().regions() > 0, "the asking search still gets it");
    assertFalse(store.inMemory(0, 0), "but it describes the terrain before the change");
  }

  @Test
  void aColumnWhoseTerrainCannotBeReadIsNotKept() {
    ToyWorld.Source source = new ToyWorld.Source();
    source.unreadable = true;
    RegionStore<ToyWorld.Column> store = store(Long.MAX_VALUE);
    assertEquals(0, column(store, 5, 5, source).regions());

    // Generated since: a store that had kept it empty would wall it off for good.
    source.unreadable = false;
    assertTrue(column(store, 5, 5, source).regions() > 0);
  }

  @Test
  void aTightBudgetEvictsTheLeastRecentlyUsed() {
    ToyWorld.Source source = new ToyWorld.Source();
    // A byte: every column but the newest is evicted at once.
    RegionStore<ToyWorld.Column> tight = store(1);
    for (int cx = 0; cx < 4; cx++) {
      column(tight, cx, 0, source);
    }
    assertEquals(3, tight.stats().evictions());
    assertTrue(tight.inMemory(3, 0), "never the column just asked for");
    assertFalse(tight.inMemory(0, 0));
  }

  @Test
  void aPinnedColumnStaysWhateverTheBudgetUntilItsLastPinIsReleased() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> tight = store(1);
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    tight.pin(alice, 0, 0, 0);
    tight.pin(bob, 0, 0, 0);
    column(tight, 0, 0, source);
    for (int cx = 1; cx < 5; cx++) {
      column(tight, cx, 0, source);
    }
    assertTrue(tight.inMemory(0, 0), "pinned, it stays");
    assertFalse(tight.inMemory(1, 0), "the others go past the budget");
    assertEquals(1, tight.stats().columnsPinned());

    tight.release(alice);
    assertTrue(tight.inMemory(0, 0), "still pinned for bob");
    tight.release(bob);
    assertFalse(tight.inMemory(0, 0), "released, it goes too");
  }

  @Test
  void pinningAgainMovesAnOwnersPin() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> tight = store(1);
    UUID owner = UUID.randomUUID();
    tight.pin(owner, 0, 0, 1);
    column(tight, 0, 0, source);
    column(tight, 1, 1, source);
    assertEquals(2, tight.stats().columnsPinned());

    // Walked on: the old surroundings are let go, the new ones held.
    tight.pin(owner, 10, 0, 1);
    column(tight, 10, 0, source);
    assertFalse(tight.inMemory(0, 0));
    assertFalse(tight.inMemory(1, 1));
    assertTrue(tight.inMemory(10, 0));
  }

  @Test
  void aPinnedColumnIsStillDroppedWhenItsTerrainChanges() {
    ToyWorld.Source source = new ToyWorld.Source();
    RegionStore<ToyWorld.Column> store = store(Long.MAX_VALUE);
    store.pin("trip", 0, 0, 0);
    column(store, 0, 0, source);
    store.invalidate(0, 0);
    assertFalse(store.inMemory(0, 0));
    column(store, 0, 0, source);
    assertEquals(2, source.reads);
    assertEquals(1, store.stats().columnsPinned(), "rebuilt, it is pinned again");
  }
}
