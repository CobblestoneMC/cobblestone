/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import net.whimxiqal.odyssey.plugin.data.EndReturnPortal;
import net.whimxiqal.odyssey.plugin.data.EndReturnPortalDao;
import net.whimxiqal.odyssey.plugin.data.PortalRegion;

/** An {@link EndReturnPortalDao} over the shared JDBC connection. Upsert is keyed by the anchor. */
final class JdbcEndReturnPortalDao implements EndReturnPortalDao {

  private static final String COLUMNS = "world, min_x, min_y, min_z, max_x, max_y, max_z, cost";
  private static final String INSERT =
      "INSERT INTO odyssey_end_return_portal (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?)";
  private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM odyssey_end_return_portal";
  private static final String EXISTS =
      "SELECT COUNT(*) FROM odyssey_end_return_portal"
          + " WHERE world = ? AND min_x = ? AND min_y = ? AND min_z = ?";
  private static final String UPDATE =
      "UPDATE odyssey_end_return_portal SET max_x = ?, max_y = ?, max_z = ?, cost = ?"
          + " WHERE world = ? AND min_x = ? AND min_y = ? AND min_z = ?";

  private final AbstractJdbcDataStore store;

  JdbcEndReturnPortalDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void upsert(EndReturnPortal portal) {
    PortalRegion region = portal.region();
    store.inTransaction(
        "upsert end-return portal",
        connection -> {
          boolean exists;
          try (PreparedStatement query = connection.prepareStatement(EXISTS)) {
            query.setString(1, region.world());
            query.setInt(2, region.minX());
            query.setInt(3, region.minY());
            query.setInt(4, region.minZ());
            try (ResultSet rows = query.executeQuery()) {
              exists = rows.next() && rows.getInt(1) > 0;
            }
          }
          if (exists) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE)) {
              update.setInt(1, region.maxX());
              update.setInt(2, region.maxY());
              update.setInt(3, region.maxZ());
              update.setDouble(4, portal.cost());
              update.setString(5, region.world());
              update.setInt(6, region.minX());
              update.setInt(7, region.minY());
              update.setInt(8, region.minZ());
              update.executeUpdate();
            }
          } else {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
              insert.setString(1, region.world());
              insert.setInt(2, region.minX());
              insert.setInt(3, region.minY());
              insert.setInt(4, region.minZ());
              insert.setInt(5, region.maxX());
              insert.setInt(6, region.maxY());
              insert.setInt(7, region.maxZ());
              insert.setDouble(8, portal.cost());
              insert.executeUpdate();
            }
          }
          return null;
        });
  }

  @Override
  public List<EndReturnPortal> all() {
    return store.query(
        "list end-return portals",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT_ALL);
              ResultSet rows = select.executeQuery()) {
            List<EndReturnPortal> result = new ArrayList<>();
            while (rows.next()) {
              PortalRegion region =
                  new PortalRegion(
                      rows.getString("world"),
                      rows.getInt("min_x"),
                      rows.getInt("min_y"),
                      rows.getInt("min_z"),
                      rows.getInt("max_x"),
                      rows.getInt("max_y"),
                      rows.getInt("max_z"));
              result.add(new EndReturnPortal(region, rows.getDouble("cost")));
            }
            return result;
          }
        });
  }

  @Override
  public int clear() {
    return store.inTransaction(
        "clear end-return portals",
        connection -> {
          try (PreparedStatement delete =
              connection.prepareStatement("DELETE FROM odyssey_end_return_portal")) {
            return delete.executeUpdate();
          }
        });
  }
}
