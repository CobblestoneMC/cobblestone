/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import net.whimxiqal.odyssey.plugin.data.PortalCacheDao;
import net.whimxiqal.odyssey.plugin.data.PortalRegion;

/** A {@link PortalCacheDao} over the shared JDBC connection. Upsert is keyed by portal anchor. */
final class JdbcPortalCacheDao implements PortalCacheDao {

  private static final String COLUMNS = "world, min_x, min_y, min_z, max_x, max_y, max_z";
  private static final String INSERT =
      "INSERT INTO odyssey_nether_portal (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?)";
  private static final String UPDATE_EXTENT =
      "UPDATE odyssey_nether_portal SET max_x = ?, max_y = ?, max_z = ?"
          + " WHERE world = ? AND min_x = ? AND min_y = ? AND min_z = ?";
  private static final String EXISTS =
      "SELECT COUNT(*) FROM odyssey_nether_portal"
          + " WHERE world = ? AND min_x = ? AND min_y = ? AND min_z = ?";
  private static final String DELETE =
      "DELETE FROM odyssey_nether_portal WHERE world = ? AND min_x = ? AND min_y = ? AND min_z = ?";

  private final AbstractJdbcDataStore store;

  JdbcPortalCacheDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void upsert(PortalRegion portal) {
    store.inTransaction(
        "upsert nether portal",
        connection -> {
          boolean exists;
          try (PreparedStatement query = connection.prepareStatement(EXISTS)) {
            query.setString(1, portal.world());
            query.setInt(2, portal.minX());
            query.setInt(3, portal.minY());
            query.setInt(4, portal.minZ());
            try (ResultSet rows = query.executeQuery()) {
              exists = rows.next() && rows.getInt(1) > 0;
            }
          }
          if (exists) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE_EXTENT)) {
              update.setInt(1, portal.maxX());
              update.setInt(2, portal.maxY());
              update.setInt(3, portal.maxZ());
              update.setString(4, portal.world());
              update.setInt(5, portal.minX());
              update.setInt(6, portal.minY());
              update.setInt(7, portal.minZ());
              update.executeUpdate();
            }
          } else {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
              bind(insert, portal);
              insert.executeUpdate();
            }
          }
          return null;
        });
  }

  @Override
  public List<PortalRegion> all() {
    return store.query(
        "list nether portals",
        connection -> {
          try (PreparedStatement select =
                  connection.prepareStatement("SELECT " + COLUMNS + " FROM odyssey_nether_portal");
              ResultSet rows = select.executeQuery()) {
            return read(rows);
          }
        });
  }

  @Override
  public List<PortalRegion> inWorld(String world) {
    return store.query(
        "list nether portals in world",
        connection -> {
          try (PreparedStatement select =
              connection.prepareStatement(
                  "SELECT " + COLUMNS + " FROM odyssey_nether_portal WHERE world = ?")) {
            select.setString(1, world);
            try (ResultSet rows = select.executeQuery()) {
              return read(rows);
            }
          }
        });
  }

  @Override
  public void remove(PortalRegion portal) {
    store.inTransaction(
        "remove nether portal",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
            delete.setString(1, portal.world());
            delete.setInt(2, portal.minX());
            delete.setInt(3, portal.minY());
            delete.setInt(4, portal.minZ());
            return delete.executeUpdate();
          }
        });
  }

  @Override
  public int clear() {
    return store.inTransaction(
        "clear nether portals",
        connection -> {
          try (PreparedStatement delete =
              connection.prepareStatement("DELETE FROM odyssey_nether_portal")) {
            return delete.executeUpdate();
          }
        });
  }

  private static List<PortalRegion> read(ResultSet rows) throws SQLException {
    List<PortalRegion> result = new ArrayList<>();
    while (rows.next()) {
      result.add(
          new PortalRegion(
              rows.getString("world"),
              rows.getInt("min_x"),
              rows.getInt("min_y"),
              rows.getInt("min_z"),
              rows.getInt("max_x"),
              rows.getInt("max_y"),
              rows.getInt("max_z")));
    }
    return result;
  }

  private static void bind(PreparedStatement statement, PortalRegion portal) throws SQLException {
    statement.setString(1, portal.world());
    statement.setInt(2, portal.minX());
    statement.setInt(3, portal.minY());
    statement.setInt(4, portal.minZ());
    statement.setInt(5, portal.maxX());
    statement.setInt(6, portal.maxY());
    statement.setInt(7, portal.maxZ());
  }
}
