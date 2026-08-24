/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The caching DAOs must be invisible to callers: same answers as the delegate, one read per scope
 * while an entry is fresh, and a write that is immediately visible to the player who made it.
 */
class CachingDaoTest {

  /** Counts reads so a test can tell a cache hit from a trip to the "database". */
  private static final class CountingLocationDao implements LocationDao {
    private final List<Location> rows = new ArrayList<>();
    private int reads;

    @Override
    public void put(Location location) {
      rows.removeIf(
          existing ->
              existing.owner().equals(location.owner()) && existing.name().equals(location.name()));
      rows.add(location);
    }

    @Override
    public boolean remove(Optional<UUID> owner, String name) {
      return rows.removeIf(row -> row.owner().equals(owner) && row.name().equals(name));
    }

    @Override
    public Optional<Location> get(Optional<UUID> owner, String name) {
      reads++;
      return rows.stream()
          .filter(row -> row.owner().equals(owner) && row.name().equals(name))
          .findFirst();
    }

    @Override
    public List<Location> ownedBy(UUID owner) {
      reads++;
      return rows.stream().filter(row -> row.owner().equals(Optional.of(owner))).toList();
    }

    @Override
    public List<Location> global() {
      reads++;
      return rows.stream().filter(Location::isGlobal).toList();
    }
  }

  private static final class CountingDeathLocationDao implements DeathLocationDao {
    private final List<DeathLocation> rows = new ArrayList<>();
    private int reads;

    @Override
    public void upsert(DeathLocation location) {
      rows.removeIf(row -> row.player().equals(location.player()));
      rows.add(location);
    }

    @Override
    public Optional<DeathLocation> get(UUID player) {
      reads++;
      return rows.stream().filter(row -> row.player().equals(player)).findFirst();
    }
  }

  private final UUID player = UUID.randomUUID();

  @Test
  void repeatedLocationReadsHitTheStoreOnce() {
    CountingLocationDao store = new CountingLocationDao();
    LocationDao dao = new CachingLocationDao(store);
    store.put(Location.personal(player, "home", "minecraft:overworld", 1, 2, 3));

    for (int i = 0; i < 20; i++) {
      assertEquals(1, dao.ownedBy(player).size());
    }
    assertEquals(1, store.reads, "tab-completion must not re-read the store per keystroke");
  }

  @Test
  void scopesAreCachedSeparately() {
    CountingLocationDao store = new CountingLocationDao();
    LocationDao dao = new CachingLocationDao(store);
    UUID other = UUID.randomUUID();

    dao.ownedBy(player);
    dao.ownedBy(other);
    dao.global();
    dao.ownedBy(player);
    assertEquals(3, store.reads, "one read per scope, then cache hits");
  }

  @Test
  void getIsAnsweredFromTheCachedScope() {
    CountingLocationDao store = new CountingLocationDao();
    LocationDao dao = new CachingLocationDao(store);
    store.put(Location.personal(player, "home", "minecraft:overworld", 1, 2, 3));

    dao.ownedBy(player);
    assertTrue(dao.get(Optional.of(player), "home").isPresent());
    assertTrue(dao.get(Optional.of(player), "missing").isEmpty());
    assertEquals(1, store.reads);
  }

  @Test
  void writingALocationIsVisibleImmediately() {
    CountingLocationDao store = new CountingLocationDao();
    LocationDao dao = new CachingLocationDao(store);
    dao.ownedBy(player); // warm the cache with an empty scope

    dao.put(Location.personal(player, "home", "minecraft:overworld", 1, 2, 3));
    assertEquals(1, dao.ownedBy(player).size(), "a player must see their own write at once");

    assertTrue(dao.remove(Optional.of(player), "home"));
    assertTrue(dao.ownedBy(player).isEmpty());
  }

  @Test
  void aWriteOnlyInvalidatesItsOwnScope() {
    CountingLocationDao store = new CountingLocationDao();
    LocationDao dao = new CachingLocationDao(store);
    dao.ownedBy(player);
    dao.global();
    int readsBefore = store.reads;

    dao.put(Location.personal(player, "home", "minecraft:overworld", 1, 2, 3));
    dao.global();
    assertEquals(readsBefore, store.reads, "the global scope was untouched, so it stays cached");
  }

  @Test
  void deathReadsAreCachedAndInvalidatedByTheNextDeath() {
    CountingDeathLocationDao store = new CountingDeathLocationDao();
    DeathLocationDao dao = new CachingDeathLocationDao(store);

    assertTrue(dao.get(player).isEmpty());
    assertTrue(dao.get(player).isEmpty());
    assertEquals(1, store.reads);

    dao.upsert(new DeathLocation(player, "minecraft:overworld", 4, 5, 6));
    assertEquals("minecraft:overworld", dao.get(player).orElseThrow().world());

    dao.upsert(new DeathLocation(player, "minecraft:the_nether", 7, 8, 9));
    assertEquals(
        "minecraft:the_nether",
        dao.get(player).orElseThrow().world(),
        "the newest death must replace the cached one");
  }
}
