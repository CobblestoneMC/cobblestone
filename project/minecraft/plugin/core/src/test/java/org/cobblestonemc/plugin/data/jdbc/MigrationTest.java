/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.Location;
import org.cobblestonemc.plugin.data.MountLocation;
import org.cobblestonemc.plugin.data.PlayerPreferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Upgrading a data store written by an earlier Cobblestone, and opening one written by a later one.
 * An "earlier Cobblestone" is this store with its newest migrations held back, which is exactly
 * what an older release ships: migrations are only ever appended.
 */
class MigrationTest {

  private static final CobblestoneLogger LOGGER = new QuietLogger();

  /** An H2 store that knows only its first {@code known} migrations. */
  private static final class OlderH2DataStore extends AbstractJdbcDataStore {
    private final int known;

    OlderH2DataStore(Path file, int known) {
      super("jdbc:h2:file:" + file.toAbsolutePath(), LOGGER);
      this.known = known;
    }

    @Override
    String driver() {
      return "org.h2.Driver";
    }

    @Override
    protected void loadDriver() throws ClassNotFoundException {
      Class.forName(driver());
    }

    @Override
    protected List<List<String>> migrations() {
      return super.migrations().subList(0, known);
    }
  }

  @Test
  void upgradesAStoreFromBeforeMountsKeepingEverythingInIt(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("cobblestone-data");
    UUID player = UUID.randomUUID();

    OlderH2DataStore older = new OlderH2DataStore(file, 3);
    older.init();
    try {
      older.locations().put(Location.personal(player, "home", "minecraft:overworld", 1, 64, 2));
      older.locations().put(Location.global("market", "minecraft:overworld", -5, 70, 9));
      older.deaths().upsert(new DeathLocation(player, "minecraft:the_nether", 3, 40, 4));
      older.playerPreferences().upsert(new PlayerPreferences(player, false));
    } finally {
      older.close();
    }
    assertEquals(3, schemaVersion(file));

    H2DataStore upgraded = new H2DataStore(file, LOGGER);
    upgraded.init();
    try {
      assertEquals(4, schemaVersion(file));
      Location home = upgraded.locations().get(Optional.of(player), "home").orElseThrow();
      assertEquals(64, home.y());
      assertTrue(upgraded.locations().get(Optional.empty(), "market").isPresent());
      assertEquals("minecraft:the_nether", upgraded.deaths().get(player).orElseThrow().world());
      assertEquals(false, upgraded.playerPreferences().get(player).orElseThrow().hud());

      UUID horse = UUID.randomUUID();
      assertTrue(upgraded.mounts().get(player).isEmpty());
      upgraded
          .mounts()
          .upsert(new MountLocation(player, horse, "minecraft:overworld", 7, 63, 8, 1_000L));
      assertEquals(horse, upgraded.mounts().get(player).orElseThrow().mount());
    } finally {
      upgraded.close();
    }

    // Opening it again applies nothing twice.
    H2DataStore reopened = new H2DataStore(file, LOGGER);
    reopened.init();
    try {
      assertEquals(4, schemaVersion(file));
      assertTrue(reopened.mounts().get(player).isPresent());
    } finally {
      reopened.close();
    }
  }

  @Test
  void anOlderCobblestoneStillOpensANewerStore(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("cobblestone-data");
    UUID player = UUID.randomUUID();

    H2DataStore newer = new H2DataStore(file, LOGGER);
    newer.init();
    try {
      newer.locations().put(Location.personal(player, "home", "minecraft:overworld", 1, 64, 2));
    } finally {
      newer.close();
    }

    // Rolling back to a release from before mounts: it ignores the table it does not know, and
    // leaves the recorded version alone so rolling forward again does not re-apply anything.
    OlderH2DataStore older = new OlderH2DataStore(file, 3);
    older.init();
    try {
      assertTrue(older.locations().get(Optional.of(player), "home").isPresent());
    } finally {
      older.close();
    }
    assertEquals(4, schemaVersion(file));
  }

  private static int schemaVersion(Path file) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection("jdbc:h2:file:" + file.toAbsolutePath());
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT version FROM cobblestone_schema_version")) {
      assertTrue(rows.next(), "a version is recorded");
      int version = rows.getInt(1);
      assertTrue(!rows.next(), "exactly one version row");
      return version;
    }
  }

  /** Swallows everything. */
  private static final class QuietLogger extends CobblestoneLogger {
    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {}

    @Override
    public void error(String message, Throwable throwable, Object... args) {}
  }
}
