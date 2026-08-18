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
import net.whimxiqal.odyssey.plugin.data.PortalLink;
import net.whimxiqal.odyssey.plugin.data.PortalRegion;

/**
 * A {@link net.whimxiqal.odyssey.plugin.data.PortalLinkDao} over the shared JDBC connection. Source
 * and destination extents are stored inline so reads need no join; writes replace a source portal's
 * whole partition.
 */
final class JdbcPortalLinkDao implements net.whimxiqal.odyssey.plugin.data.PortalLinkDao {

  private static final String COLUMNS =
      "from_world, from_min_x, from_min_y, from_min_z, from_max_x, from_max_y, from_max_z,"
          + " sub_min_x, sub_min_y, sub_min_z, sub_max_x, sub_max_y, sub_max_z,"
          + " to_world, to_min_x, to_min_y, to_min_z, to_max_x, to_max_y, to_max_z, cost";
  private static final String INSERT =
      "INSERT INTO odyssey_nether_portal_link ("
          + COLUMNS
          + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
  private static final String DELETE_SOURCE =
      "DELETE FROM odyssey_nether_portal_link"
          + " WHERE from_world = ? AND from_min_x = ? AND from_min_y = ? AND from_min_z = ?";
  private static final String DELETE_REFERENCING =
      "DELETE FROM odyssey_nether_portal_link"
          + " WHERE (from_world = ? AND from_min_x = ? AND from_min_y = ? AND from_min_z = ?)"
          + " OR (to_world = ? AND to_min_x = ? AND to_min_y = ? AND to_min_z = ?)";

  private final AbstractJdbcDataStore store;

  JdbcPortalLinkDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void replaceForSource(PortalRegion source, List<PortalLink> links) {
    store.inTransaction(
        "replace nether portal links",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(DELETE_SOURCE)) {
            delete.setString(1, source.world());
            delete.setInt(2, source.minX());
            delete.setInt(3, source.minY());
            delete.setInt(4, source.minZ());
            delete.executeUpdate();
          }
          try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            for (PortalLink link : links) {
              bind(insert, link);
              insert.addBatch();
            }
            insert.executeBatch();
          }
          return null;
        });
  }

  @Override
  public List<PortalLink> all() {
    return store.query(
        "list nether portal links",
        connection -> {
          try (PreparedStatement select =
                  connection.prepareStatement(
                      "SELECT " + COLUMNS + " FROM odyssey_nether_portal_link");
              ResultSet rows = select.executeQuery()) {
            List<PortalLink> result = new ArrayList<>();
            while (rows.next()) {
              PortalRegion source =
                  box(
                      rows.getString("from_world"),
                      rows,
                      "from_min_x",
                      "from_min_y",
                      "from_min_z",
                      "from_max_x",
                      "from_max_y",
                      "from_max_z");
              PortalRegion subRegion =
                  box(
                      rows.getString("from_world"),
                      rows,
                      "sub_min_x",
                      "sub_min_y",
                      "sub_min_z",
                      "sub_max_x",
                      "sub_max_y",
                      "sub_max_z");
              PortalRegion dest =
                  box(
                      rows.getString("to_world"),
                      rows,
                      "to_min_x",
                      "to_min_y",
                      "to_min_z",
                      "to_max_x",
                      "to_max_y",
                      "to_max_z");
              result.add(new PortalLink(source, subRegion, dest, rows.getDouble("cost")));
            }
            return result;
          }
        });
  }

  @Override
  public int removeReferencing(PortalRegion portal) {
    return store.inTransaction(
        "cull nether portal links",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(DELETE_REFERENCING)) {
            delete.setString(1, portal.world());
            delete.setInt(2, portal.minX());
            delete.setInt(3, portal.minY());
            delete.setInt(4, portal.minZ());
            delete.setString(5, portal.world());
            delete.setInt(6, portal.minX());
            delete.setInt(7, portal.minY());
            delete.setInt(8, portal.minZ());
            return delete.executeUpdate();
          }
        });
  }

  @Override
  public int clear() {
    return store.inTransaction(
        "clear nether portal links",
        connection -> {
          try (PreparedStatement delete =
              connection.prepareStatement("DELETE FROM odyssey_nether_portal_link")) {
            return delete.executeUpdate();
          }
        });
  }

  private static PortalRegion box(
      String world,
      ResultSet rows,
      String minX,
      String minY,
      String minZ,
      String maxX,
      String maxY,
      String maxZ)
      throws SQLException {
    return new PortalRegion(
        world,
        rows.getInt(minX),
        rows.getInt(minY),
        rows.getInt(minZ),
        rows.getInt(maxX),
        rows.getInt(maxY),
        rows.getInt(maxZ));
  }

  private static void bind(PreparedStatement statement, PortalLink link) throws SQLException {
    PortalRegion source = link.source();
    PortalRegion sub = link.subRegion();
    PortalRegion dest = link.dest();
    statement.setString(1, source.world());
    statement.setInt(2, source.minX());
    statement.setInt(3, source.minY());
    statement.setInt(4, source.minZ());
    statement.setInt(5, source.maxX());
    statement.setInt(6, source.maxY());
    statement.setInt(7, source.maxZ());
    statement.setInt(8, sub.minX());
    statement.setInt(9, sub.minY());
    statement.setInt(10, sub.minZ());
    statement.setInt(11, sub.maxX());
    statement.setInt(12, sub.maxY());
    statement.setInt(13, sub.maxZ());
    statement.setString(14, dest.world());
    statement.setInt(15, dest.minX());
    statement.setInt(16, dest.minY());
    statement.setInt(17, dest.minZ());
    statement.setInt(18, dest.maxX());
    statement.setInt(19, dest.maxY());
    statement.setInt(20, dest.maxZ());
    statement.setDouble(21, link.cost());
  }
}
