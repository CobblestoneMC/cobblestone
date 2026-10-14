/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;

/**
 * A {@link MountDao} over the shared JDBC connection. Like the other DAOs here it sticks to
 * dialect-neutral SQL: the upsert is a delete-then-insert inside a transaction rather than a
 * backend-specific {@code MERGE}/{@code ON CONFLICT}, the newest row is picked with {@link
 * java.sql.Statement#setMaxRows} rather than {@code LIMIT}, and the oldest rows are pruned by
 * reading them first rather than with a self-referencing delete.
 */
final class JdbcMountDao implements MountDao {

  /**
   * How many mounts are remembered per player. Only the newest is navigated to for now; the table
   * holds one row per player and mount, so keeping more needs no new migration.
   */
  static final int KEPT_PER_PLAYER = 1;

  private static final String INSERT =
      "INSERT INTO cobblestone_mount (player, mount, world, x, y, z, ridden_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";
  private static final String DELETE =
      "DELETE FROM cobblestone_mount WHERE player = ? AND mount = ?";
  private static final String NEWEST_FIRST =
      "SELECT player, mount, world, x, y, z, ridden_at FROM cobblestone_mount WHERE player = ?"
          + " ORDER BY ridden_at DESC, mount";
  private static final String MOVE =
      "UPDATE cobblestone_mount SET world = ?, x = ?, y = ?, z = ? WHERE mount = ?";
  private static final String REMOVE = "DELETE FROM cobblestone_mount WHERE mount = ?";

  private final AbstractJdbcDataStore store;

  JdbcMountDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void upsert(MountLocation location) {
    store.inTransaction(
        "put mount",
        connection -> {
          String player = location.player().toString();
          delete(connection, player, location.mount().toString());
          try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            insert.setString(1, player);
            insert.setString(2, location.mount().toString());
            insert.setString(3, location.world());
            insert.setInt(4, location.x());
            insert.setInt(5, location.y());
            insert.setInt(6, location.z());
            insert.setLong(7, location.riddenAt());
            insert.executeUpdate();
          }
          // Forget whatever has fallen off the end of the player's history. Read first, then
          // delete, so no row is deleted out from under an open result set.
          List<String> forgotten = new ArrayList<>();
          try (PreparedStatement select = connection.prepareStatement(NEWEST_FIRST)) {
            select.setString(1, player);
            try (ResultSet rows = select.executeQuery()) {
              for (int seen = 0; rows.next(); seen++) {
                if (seen >= KEPT_PER_PLAYER) {
                  forgotten.add(rows.getString("mount"));
                }
              }
            }
          }
          for (String mount : forgotten) {
            delete(connection, player, mount);
          }
          return null;
        });
  }

  private static void delete(Connection connection, String player, String mount)
      throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
      delete.setString(1, player);
      delete.setString(2, mount);
      delete.executeUpdate();
    }
  }

  @Override
  public Optional<MountLocation> get(UUID player) {
    return store.query(
        "get mount",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(NEWEST_FIRST)) {
            select.setMaxRows(1);
            select.setString(1, player.toString());
            try (ResultSet rows = select.executeQuery()) {
              if (!rows.next()) {
                return Optional.<MountLocation>empty();
              }
              return Optional.of(
                  new MountLocation(
                      UUID.fromString(rows.getString("player")),
                      UUID.fromString(rows.getString("mount")),
                      rows.getString("world"),
                      rows.getInt("x"),
                      rows.getInt("y"),
                      rows.getInt("z"),
                      rows.getLong("ridden_at")));
            }
          }
        });
  }

  @Override
  public void move(UUID mount, String world, int x, int y, int z) {
    store.inTransaction(
        "move mount",
        connection -> {
          try (PreparedStatement update = connection.prepareStatement(MOVE)) {
            update.setString(1, world);
            update.setInt(2, x);
            update.setInt(3, y);
            update.setInt(4, z);
            update.setString(5, mount.toString());
            update.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public void remove(UUID mount) {
    store.inTransaction(
        "remove mount",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(REMOVE)) {
            delete.setString(1, mount.toString());
            delete.executeUpdate();
          }
          return null;
        });
  }
}
