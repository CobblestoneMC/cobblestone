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
import net.whimxiqal.odyssey.plugin.data.PortalTransition;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;

/**
 * A {@link PortalTransitionDao} over the shared JDBC connection. {@link #upsert} is keyed by the
 * source portal anchor (from-world + minimum corner): a known source has its arrival and cost
 * updated, an unknown source is inserted, so re-walking a portal never creates duplicates.
 */
final class JdbcPortalTransitionDao implements PortalTransitionDao {

  private static final String COLUMNS =
      "from_world, min_x, min_y, min_z, max_x, max_y, max_z, to_world, to_x, to_y, to_z, cost";
  private static final String INSERT =
      "INSERT INTO odyssey_portal_transition (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
  private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM odyssey_portal_transition";
  private static final String EXISTS =
      "SELECT COUNT(*) FROM odyssey_portal_transition"
          + " WHERE from_world = ? AND min_x = ? AND min_y = ? AND min_z = ?";
  private static final String UPDATE =
      "UPDATE odyssey_portal_transition SET max_x = ?, max_y = ?, max_z = ?, to_world = ?,"
          + " to_x = ?, to_y = ?, to_z = ?, cost = ?"
          + " WHERE from_world = ? AND min_x = ? AND min_y = ? AND min_z = ?";

  private final AbstractJdbcDataStore store;

  JdbcPortalTransitionDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void upsert(PortalTransition transition) {
    store.inTransaction(
        "upsert portal transition",
        connection -> {
          boolean exists;
          try (PreparedStatement query = connection.prepareStatement(EXISTS)) {
            query.setString(1, transition.fromWorld());
            query.setInt(2, transition.minX());
            query.setInt(3, transition.minY());
            query.setInt(4, transition.minZ());
            try (ResultSet rows = query.executeQuery()) {
              exists = rows.next() && rows.getInt(1) > 0;
            }
          }
          if (exists) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE)) {
              update.setInt(1, transition.maxX());
              update.setInt(2, transition.maxY());
              update.setInt(3, transition.maxZ());
              update.setString(4, transition.toWorld());
              update.setInt(5, transition.toX());
              update.setInt(6, transition.toY());
              update.setInt(7, transition.toZ());
              update.setDouble(8, transition.cost());
              update.setString(9, transition.fromWorld());
              update.setInt(10, transition.minX());
              update.setInt(11, transition.minY());
              update.setInt(12, transition.minZ());
              update.executeUpdate();
            }
          } else {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
              insert.setString(1, transition.fromWorld());
              insert.setInt(2, transition.minX());
              insert.setInt(3, transition.minY());
              insert.setInt(4, transition.minZ());
              insert.setInt(5, transition.maxX());
              insert.setInt(6, transition.maxY());
              insert.setInt(7, transition.maxZ());
              insert.setString(8, transition.toWorld());
              insert.setInt(9, transition.toX());
              insert.setInt(10, transition.toY());
              insert.setInt(11, transition.toZ());
              insert.setDouble(12, transition.cost());
              insert.executeUpdate();
            }
          }
          return null;
        });
  }

  @Override
  public List<PortalTransition> all() {
    return store.query(
        "list portal transitions",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT_ALL);
              ResultSet rows = select.executeQuery()) {
            List<PortalTransition> result = new ArrayList<>();
            while (rows.next()) {
              result.add(
                  new PortalTransition(
                      rows.getString("from_world"),
                      rows.getInt("min_x"),
                      rows.getInt("min_y"),
                      rows.getInt("min_z"),
                      rows.getInt("max_x"),
                      rows.getInt("max_y"),
                      rows.getInt("max_z"),
                      rows.getString("to_world"),
                      rows.getInt("to_x"),
                      rows.getInt("to_y"),
                      rows.getInt("to_z"),
                      rows.getDouble("cost")));
            }
            return result;
          }
        });
  }

  @Override
  public int clear() {
    return store.inTransaction(
        "clear portal transitions",
        connection -> {
          try (PreparedStatement delete =
              connection.prepareStatement("DELETE FROM odyssey_portal_transition")) {
            return delete.executeUpdate();
          }
        });
  }
}
