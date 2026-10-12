/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.plugin.data.jdbc.H2DataStore;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One behavioral contract every {@link DataStore} backend must satisfy, run against the embedded
 * JDBC engine both bare and behind the caching DAOs the plugin really uses. Backends are supplied
 * as a {@code (file, logger) -> DataStore} factory so the "restart" case can reopen a fresh store
 * over the same on-disk file.
 */
class DataStoreContractTest {

  private static final CobblestoneLogger LOGGER = new NoopLogger();

  /** A backend under test: a display name plus a factory over a database file. */
  record Backend(String name, BiFunction<Path, CobblestoneLogger, DataStore> open) {
    @Override
    public @NotNull String toString() {
      return name;
    }
  }

  static List<Arguments> backends() {
    return List.of(
        Arguments.of(new Backend("h2", H2DataStore::new)),
        // The store the plugin actually gets: the same backend behind the caching DAOs, so the
        // contract also pins that caching stays invisible (writes invalidate what they touch).
        Arguments.of(
            new Backend(
                "h2 (cached)", (file, logger) -> DataStores.create(DataBackend.H2, file, logger))));
  }

  private static DataStore opened(Backend backend, Path dir) {
    DataStore store = backend.open().apply(dir.resolve("cobblestone-data"), LOGGER);
    store.init();
    return store;
  }

  @ParameterizedTest
  @MethodSource("backends")
  void putThenGetPersonalLocation(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID owner = UUID.randomUUID();
      store.locations().put(Location.personal(owner, "home", "minecraft:overworld", 10, 64, -20));

      Optional<Location> found = store.locations().get(Optional.of(owner), "home");
      assertTrue(found.isPresent(), "location should be retrievable");
      assertEquals("minecraft:overworld", found.get().world());
      assertEquals(10, found.get().x());
      assertEquals(64, found.get().y());
      assertEquals(-20, found.get().z());
      assertFalse(found.get().isGlobal());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void putThenGetGlobalLocation(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      store.locations().put(Location.global("spawn", "minecraft:overworld", 0, 70, 0));

      Optional<Location> found = store.locations().get(Optional.empty(), "spawn");
      assertTrue(found.isPresent());
      assertTrue(found.get().isGlobal());
      assertEquals(List.of(found.get()), store.locations().global());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void putOverwritesExistingLocation(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID owner = UUID.randomUUID();
      store.locations().put(Location.personal(owner, "camp", "minecraft:overworld", 1, 1, 1));
      store.locations().put(Location.personal(owner, "camp", "minecraft:the_nether", 2, 2, 2));

      Optional<Location> found = store.locations().get(Optional.of(owner), "camp");
      assertTrue(found.isPresent());
      assertEquals("minecraft:the_nether", found.get().world());
      assertEquals(2, found.get().x());
      assertEquals(
          1, store.locations().ownedBy(owner).size(), "upsert must not create a duplicate");
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void removeReportsWhetherRowExisted(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID owner = UUID.randomUUID();
      store.locations().put(Location.personal(owner, "temp", "minecraft:overworld", 5, 5, 5));

      assertTrue(store.locations().remove(Optional.of(owner), "temp"), "first remove hits a row");
      assertFalse(store.locations().remove(Optional.of(owner), "temp"), "second remove is a no-op");
      assertTrue(store.locations().get(Optional.of(owner), "temp").isEmpty());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void personalAndGlobalScopesAreIsolated(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID owner = UUID.randomUUID();
      store.locations().put(Location.personal(owner, "shared", "minecraft:overworld", 1, 1, 1));
      store.locations().put(Location.global("shared", "minecraft:overworld", 9, 9, 9));

      // Same name in two scopes coexist and do not leak into each other's listings.
      assertEquals(1, store.locations().ownedBy(owner).size());
      assertEquals(1, store.locations().global().size());
      assertEquals(1, store.locations().get(Optional.of(owner), "shared").get().x());
      assertEquals(9, store.locations().get(Optional.empty(), "shared").get().x());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void portalTransitionUpsertsBySourceAndClears(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      PortalTransition portal =
          new PortalTransition(
              "minecraft:overworld", 10, 60, 10, 11, 62, 10, "minecraft:the_nether", 1, 60, 1, 5.0);
      store.portalTransitions().upsert(portal);
      store.portalTransitions().upsert(portal); // same source — must not duplicate
      assertEquals(List.of(portal), store.portalTransitions().all());

      // A new arrival from the SAME source updates the existing row, not a second one.
      PortalTransition relinked =
          new PortalTransition(
              "minecraft:overworld", 10, 60, 10, 11, 62, 10, "minecraft:the_nether", 2, 60, 2, 5.0);
      store.portalTransitions().upsert(relinked);
      assertEquals(List.of(relinked), store.portalTransitions().all());

      // A different source is a distinct row.
      store
          .portalTransitions()
          .upsert(
              new PortalTransition(
                  "minecraft:overworld",
                  99,
                  60,
                  99,
                  99,
                  62,
                  99,
                  "minecraft:the_nether",
                  3,
                  60,
                  3,
                  5.0));
      assertEquals(2, store.portalTransitions().all().size());

      assertEquals(2, store.portalTransitions().clear());
      assertTrue(store.portalTransitions().all().isEmpty());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void endReturnPortalUpsertsByAnchorAndClears(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      EndReturnPortal portal =
          new EndReturnPortal(new PortalRegion("minecraft:the_end", 0, 60, 0, 2, 62, 2), 5.0);
      store.endReturnPortals().upsert(portal);
      store.endReturnPortals().upsert(portal); // same anchor — must not duplicate
      assertEquals(List.of(portal), store.endReturnPortals().all());

      // Same anchor, updated extent/cost overwrites in place.
      EndReturnPortal grown =
          new EndReturnPortal(new PortalRegion("minecraft:the_end", 0, 60, 0, 3, 63, 3), 7.0);
      store.endReturnPortals().upsert(grown);
      assertEquals(List.of(grown), store.endReturnPortals().all());

      assertEquals(1, store.endReturnPortals().clear());
      assertTrue(store.endReturnPortals().all().isEmpty());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void locationSurvivesReopen(Backend backend, @TempDir Path dir) {
    UUID owner = UUID.randomUUID();
    DataStore first = opened(backend, dir);
    try {
      first.locations().put(Location.personal(owner, "base", "minecraft:overworld", 100, 63, 200));
    } finally {
      first.close();
    }

    // Simulate a server restart: a brand-new store over the same file must see the location.
    DataStore second = opened(backend, dir);
    try {
      Optional<Location> found = second.locations().get(Optional.of(owner), "base");
      assertTrue(found.isPresent(), "location must persist across a restart");
      assertEquals(100, found.get().x());
      assertEquals(200, found.get().z());
    } finally {
      second.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void lastDeathIsStoredPerPlayer(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID player = UUID.randomUUID();
      UUID other = UUID.randomUUID();
      assertTrue(store.deaths().get(player).isEmpty(), "a player who has not died has no record");

      store.deaths().upsert(new DeathLocation(player, "minecraft:overworld", 1, 64, 2));
      store.deaths().upsert(new DeathLocation(other, "minecraft:the_nether", 9, 9, 9));

      Optional<DeathLocation> found = store.deaths().get(player);
      assertTrue(found.isPresent());
      assertEquals("minecraft:overworld", found.get().world());
      assertEquals(1, found.get().x());
      assertEquals(64, found.get().y());
      assertEquals(2, found.get().z());
      assertEquals("minecraft:the_nether", store.deaths().get(other).orElseThrow().world());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void deathOverwritesThePreviousOne(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID player = UUID.randomUUID();
      store.deaths().upsert(new DeathLocation(player, "minecraft:overworld", 1, 1, 1));
      store.deaths().upsert(new DeathLocation(player, "minecraft:the_end", 2, 2, 2));

      DeathLocation found = store.deaths().get(player).orElseThrow();
      assertEquals("minecraft:the_end", found.world(), "only the latest death is kept");
      assertEquals(2, found.x());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void deathSurvivesReopen(Backend backend, @TempDir Path dir) {
    UUID player = UUID.randomUUID();
    DataStore first = opened(backend, dir);
    try {
      first.deaths().upsert(new DeathLocation(player, "minecraft:overworld", -30, 12, 400));
    } finally {
      first.close();
    }

    DataStore second = opened(backend, dir);
    try {
      DeathLocation found = second.deaths().get(player).orElseThrow();
      assertEquals(-30, found.x());
      assertEquals(400, found.z());
    } finally {
      second.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void newestMountIsTheOneFound(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID player = UUID.randomUUID();
      UUID horse = UUID.randomUUID();
      UUID camel = UUID.randomUUID();
      assertTrue(store.mounts().get(player).isEmpty(), "a player who never rode has no record");

      store.mounts().upsert(new MountLocation(player, horse, "minecraft:overworld", 1, 64, 2, 100));
      assertEquals(horse, store.mounts().get(player).orElseThrow().mount());

      store.mounts().upsert(new MountLocation(player, camel, "minecraft:overworld", 5, 70, 6, 200));
      MountLocation found = store.mounts().get(player).orElseThrow();
      assertEquals(camel, found.mount(), "the newest mount wins");
      assertEquals(5, found.x());
      assertEquals(200, found.riddenAt());

      // Riding the horse again makes it the newest, replacing its old record rather than adding
      // one.
      store.mounts().upsert(new MountLocation(player, horse, "minecraft:overworld", 9, 65, 9, 300));
      found = store.mounts().get(player).orElseThrow();
      assertEquals(horse, found.mount());
      assertEquals(9, found.x());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void whenTheNewestMountDiesTheOneBeforeItIsFound(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID player = UUID.randomUUID();
      UUID horse = UUID.randomUUID();
      UUID camel = UUID.randomUUID();
      store.mounts().upsert(new MountLocation(player, horse, "minecraft:overworld", 1, 64, 2, 100));
      store.mounts().upsert(new MountLocation(player, camel, "minecraft:overworld", 5, 70, 6, 200));

      store.mounts().remove(camel);
      assertEquals(horse, store.mounts().get(player).orElseThrow().mount());

      store.mounts().remove(horse);
      assertTrue(store.mounts().get(player).isEmpty());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void onlyTheMostRecentFewMountsAreKept(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID player = UUID.randomUUID();
      List<UUID> ridden = new ArrayList<>();
      for (int i = 0; i < 7; i++) {
        UUID mount = UUID.randomUUID();
        ridden.add(mount);
        store.mounts().upsert(new MountLocation(player, mount, "minecraft:overworld", i, 64, 0, i));
      }
      // Kill them newest first: the five newest are remembered, the two oldest are not.
      for (int i = 6; i >= 2; i--) {
        assertEquals(ridden.get(i), store.mounts().get(player).orElseThrow().mount());
        store.mounts().remove(ridden.get(i));
      }
      assertTrue(store.mounts().get(player).isEmpty(), "the oldest two were forgotten");
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void mountMovesAndRemovalsApplyToEveryRider(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID ann = UUID.randomUUID();
      UUID bob = UUID.randomUUID();
      UUID horse = UUID.randomUUID();
      store.mounts().upsert(new MountLocation(ann, horse, "minecraft:overworld", 0, 64, 0, 1));
      store.mounts().upsert(new MountLocation(bob, horse, "minecraft:overworld", 0, 64, 0, 2));

      store.mounts().move(horse, "minecraft:the_nether", -8, 40, 12);
      for (UUID rider : List.of(ann, bob)) {
        MountLocation found = store.mounts().get(rider).orElseThrow();
        assertEquals("minecraft:the_nether", found.world());
        assertEquals(-8, found.x());
        assertEquals(40, found.y());
        assertEquals(12, found.z());
      }

      store.mounts().remove(horse);
      assertTrue(store.mounts().get(ann).isEmpty());
      assertTrue(store.mounts().get(bob).isEmpty());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void playerPreferencesRoundTripAndOverwrite(Backend backend, @TempDir Path dir) {
    DataStore store = opened(backend, dir);
    try {
      UUID player = UUID.randomUUID();
      assertTrue(
          store.playerPreferences().get(player).isEmpty(),
          "a player who changed nothing has no record");

      store.playerPreferences().upsert(new PlayerPreferences(player, false));
      assertFalse(store.playerPreferences().get(player).orElseThrow().hud());

      store.playerPreferences().upsert(new PlayerPreferences(player, true));
      assertTrue(store.playerPreferences().get(player).orElseThrow().hud());
    } finally {
      store.close();
    }
  }

  @ParameterizedTest
  @MethodSource("backends")
  void playerPreferencesSurviveReopen(Backend backend, @TempDir Path dir) {
    UUID player = UUID.randomUUID();
    DataStore first = opened(backend, dir);
    try {
      first.playerPreferences().upsert(new PlayerPreferences(player, false));
    } finally {
      first.close();
    }
    DataStore second = opened(backend, dir);
    try {
      assertFalse(second.playerPreferences().get(player).orElseThrow().hud());
    } finally {
      second.close();
    }
  }
}
