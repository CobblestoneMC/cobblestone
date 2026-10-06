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
import org.cobblestonemc.plugin.data.PlayerPreferences;
import org.cobblestonemc.plugin.data.PlayerPreferencesDao;

/**
 * A {@link PlayerPreferencesDao} over the shared JDBC connection, with the same dialect-neutral
 * delete-then-insert upsert as the other DAOs here.
 */
final class JdbcPlayerPreferencesDao implements PlayerPreferencesDao {

  private static final String INSERT =
      "INSERT INTO cobblestone_player_preferences (player, sidebar) VALUES (?, ?)";
  private static final String DELETE =
      "DELETE FROM cobblestone_player_preferences WHERE player = ?";
  private static final String SELECT =
      "SELECT player, sidebar FROM cobblestone_player_preferences WHERE player = ?";

  private final AbstractJdbcDataStore store;

  JdbcPlayerPreferencesDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void upsert(PlayerPreferences preferences) {
    store.inTransaction(
        "put player preferences",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
            delete.setString(1, preferences.player().toString());
            delete.executeUpdate();
          }
          try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            insert.setString(1, preferences.player().toString());
            insert.setBoolean(2, preferences.sidebar());
            insert.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public Optional<PlayerPreferences> get(UUID player) {
    return store.query(
        "get player preferences",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT)) {
            select.setString(1, player.toString());
            try (ResultSet rows = select.executeQuery()) {
              if (!rows.next()) {
                return Optional.<PlayerPreferences>empty();
              }
              return Optional.of(
                  new PlayerPreferences(
                      UUID.fromString(rows.getString("player")), rows.getBoolean("sidebar")));
            }
          }
        });
  }
}
