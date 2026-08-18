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
import net.whimxiqal.odyssey.plugin.data.GatewayDao;
import net.whimxiqal.odyssey.plugin.data.GatewayTransition;

/** A {@link GatewayDao} over the shared JDBC connection. Upsert is keyed by the gateway block. */
final class JdbcGatewayDao implements GatewayDao {

  private static final String COLUMNS = "world, x, y, z, to_world, to_x, to_y, to_z, cost";
  private static final String INSERT =
      "INSERT INTO odyssey_end_gateway (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)";
  private static final String UPDATE_EXIT =
      "UPDATE odyssey_end_gateway SET to_world = ?, to_x = ?, to_y = ?, to_z = ?, cost = ?"
          + " WHERE world = ? AND x = ? AND y = ? AND z = ?";
  private static final String EXISTS =
      "SELECT COUNT(*) FROM odyssey_end_gateway WHERE world = ? AND x = ? AND y = ? AND z = ?";

  private final AbstractJdbcDataStore store;

  JdbcGatewayDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void upsert(GatewayTransition gateway) {
    store.inTransaction(
        "upsert end gateway",
        connection -> {
          boolean exists;
          try (PreparedStatement query = connection.prepareStatement(EXISTS)) {
            query.setString(1, gateway.world());
            query.setInt(2, gateway.x());
            query.setInt(3, gateway.y());
            query.setInt(4, gateway.z());
            try (ResultSet rows = query.executeQuery()) {
              exists = rows.next() && rows.getInt(1) > 0;
            }
          }
          if (exists) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE_EXIT)) {
              update.setString(1, gateway.toWorld());
              update.setInt(2, gateway.toX());
              update.setInt(3, gateway.toY());
              update.setInt(4, gateway.toZ());
              update.setDouble(5, gateway.cost());
              update.setString(6, gateway.world());
              update.setInt(7, gateway.x());
              update.setInt(8, gateway.y());
              update.setInt(9, gateway.z());
              update.executeUpdate();
            }
          } else {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
              insert.setString(1, gateway.world());
              insert.setInt(2, gateway.x());
              insert.setInt(3, gateway.y());
              insert.setInt(4, gateway.z());
              insert.setString(5, gateway.toWorld());
              insert.setInt(6, gateway.toX());
              insert.setInt(7, gateway.toY());
              insert.setInt(8, gateway.toZ());
              insert.setDouble(9, gateway.cost());
              insert.executeUpdate();
            }
          }
          return null;
        });
  }

  @Override
  public List<GatewayTransition> all() {
    return store.query(
        "list end gateways",
        connection -> {
          try (PreparedStatement select =
                  connection.prepareStatement("SELECT " + COLUMNS + " FROM odyssey_end_gateway");
              ResultSet rows = select.executeQuery()) {
            List<GatewayTransition> result = new ArrayList<>();
            while (rows.next()) {
              result.add(
                  new GatewayTransition(
                      rows.getString("world"),
                      rows.getInt("x"),
                      rows.getInt("y"),
                      rows.getInt("z"),
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
        "clear end gateways",
        connection -> {
          try (PreparedStatement delete =
              connection.prepareStatement("DELETE FROM odyssey_end_gateway")) {
            return delete.executeUpdate();
          }
        });
  }
}
