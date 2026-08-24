/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.plugin.data.DeathLocation;
import org.cobblestonemc.plugin.data.DeathLocationDao;

/**
 * A {@link DeathLocationDao} over the shared JDBC connection. Like the other DAOs here the upsert
 * is a dialect-neutral delete-then-insert inside a transaction rather than a backend-specific
 * {@code MERGE}/{@code ON CONFLICT}.
 */
final class JdbcDeathLocationDao implements DeathLocationDao {

  private static final String INSERT =
      "INSERT INTO cobblestone_death (player, world, x, y, z) VALUES (?, ?, ?, ?, ?)";
  private static final String DELETE = "DELETE FROM cobblestone_death WHERE player = ?";
  private static final String SELECT =
      "SELECT player, world, x, y, z FROM cobblestone_death WHERE player = ?";

  private final AbstractJdbcDataStore store;

  JdbcDeathLocationDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void upsert(DeathLocation location) {
    store.inTransaction(
        "put death location",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
            delete.setString(1, location.player().toString());
            delete.executeUpdate();
          }
          try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            insert.setString(1, location.player().toString());
            insert.setString(2, location.world());
            insert.setInt(3, location.x());
            insert.setInt(4, location.y());
            insert.setInt(5, location.z());
            insert.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public Optional<DeathLocation> get(UUID player) {
    return store.query(
        "get death location",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT)) {
            select.setString(1, player.toString());
            try (ResultSet rows = select.executeQuery()) {
              if (!rows.next()) {
                return Optional.<DeathLocation>empty();
              }
              return Optional.of(
                  new DeathLocation(
                      UUID.fromString(rows.getString("player")),
                      rows.getString("world"),
                      rows.getInt("x"),
                      rows.getInt("y"),
                      rows.getInt("z")));
            }
          }
        });
  }
}
