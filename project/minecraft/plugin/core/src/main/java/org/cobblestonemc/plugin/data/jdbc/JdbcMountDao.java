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
import org.cobblestonemc.plugin.data.MountDao;
import org.cobblestonemc.plugin.data.MountLocation;

/**
 * A {@link MountDao} over the shared JDBC connection. Like the other DAOs here the upsert is a
 * dialect-neutral delete-then-insert inside a transaction rather than a backend-specific {@code
 * MERGE}/{@code ON CONFLICT}.
 */
final class JdbcMountDao implements MountDao {

  private static final String INSERT =
      "INSERT INTO cobblestone_mount (player, mount, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)";
  private static final String DELETE = "DELETE FROM cobblestone_mount WHERE player = ?";
  private static final String SELECT =
      "SELECT player, mount, world, x, y, z FROM cobblestone_mount WHERE player = ?";
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
          try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
            delete.setString(1, location.player().toString());
            delete.executeUpdate();
          }
          try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            insert.setString(1, location.player().toString());
            insert.setString(2, location.mount().toString());
            insert.setString(3, location.world());
            insert.setInt(4, location.x());
            insert.setInt(5, location.y());
            insert.setInt(6, location.z());
            insert.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public Optional<MountLocation> get(UUID player) {
    return store.query(
        "get mount",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT)) {
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
                      rows.getInt("z")));
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
