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
import net.whimxiqal.odyssey.plugin.data.PortalTransition;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;

/**
 * A {@link PortalTransitionDao} over the shared JDBC connection. {@link #add} is idempotent — it
 * checks for an identical row first — so re-walking a known portal does not create duplicates.
 */
final class JdbcPortalTransitionDao implements PortalTransitionDao {

  private static final String COLUMNS =
      "from_world, min_x, min_y, min_z, max_x, max_y, max_z, to_world, to_x, to_y, to_z, cost";
  private static final String INSERT =
      "INSERT INTO odyssey_portal_transition (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
  private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM odyssey_portal_transition";
  private static final String EXISTS =
      "SELECT COUNT(*) FROM odyssey_portal_transition WHERE from_world = ? AND min_x = ? AND min_y = ?"
          + " AND min_z = ? AND max_x = ? AND max_y = ? AND max_z = ? AND to_world = ? AND to_x = ?"
          + " AND to_y = ? AND to_z = ?";

  private final AbstractJdbcDataStore store;

  JdbcPortalTransitionDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void add(PortalTransition transition) {
    store.inTransaction("add portal transition", connection -> {
      try (PreparedStatement exists = connection.prepareStatement(EXISTS)) {
        bindKey(exists, transition);
        try (ResultSet rows = exists.executeQuery()) {
          if (rows.next() && rows.getInt(1) > 0) {
            return null; // already recorded
          }
        }
      }
      try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
        bindKey(insert, transition);
        insert.setDouble(12, transition.cost());
        insert.executeUpdate();
      }
      return null;
    });
  }

  @Override
  public List<PortalTransition> all() {
    return store.query("list portal transitions", connection -> {
      try (PreparedStatement select = connection.prepareStatement(SELECT_ALL);
          ResultSet rows = select.executeQuery()) {
        List<PortalTransition> result = new ArrayList<>();
        while (rows.next()) {
          result.add(new PortalTransition(
              rows.getString("from_world"),
              rows.getInt("min_x"), rows.getInt("min_y"), rows.getInt("min_z"),
              rows.getInt("max_x"), rows.getInt("max_y"), rows.getInt("max_z"),
              rows.getString("to_world"),
              rows.getInt("to_x"), rows.getInt("to_y"), rows.getInt("to_z"),
              rows.getDouble("cost")));
        }
        return result;
      }
    });
  }

  @Override
  public int clear() {
    return store.inTransaction("clear portal transitions", connection -> {
      try (PreparedStatement delete = connection.prepareStatement("DELETE FROM odyssey_portal_transition")) {
        return delete.executeUpdate();
      }
    });
  }

  /** Binds the 11 identity columns (everything but cost) in order, starting at index 1. */
  private static void bindKey(PreparedStatement statement, PortalTransition transition) throws SQLException {
    statement.setString(1, transition.fromWorld());
    statement.setInt(2, transition.minX());
    statement.setInt(3, transition.minY());
    statement.setInt(4, transition.minZ());
    statement.setInt(5, transition.maxX());
    statement.setInt(6, transition.maxY());
    statement.setInt(7, transition.maxZ());
    statement.setString(8, transition.toWorld());
    statement.setInt(9, transition.toX());
    statement.setInt(10, transition.toY());
    statement.setInt(11, transition.toZ());
  }
}
