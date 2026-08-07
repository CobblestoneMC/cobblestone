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
import java.util.Optional;
import java.util.UUID;
import net.whimxiqal.odyssey.plugin.data.Waypoint;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;

/**
 * A {@link WaypointDao} over the shared JDBC connection. Global waypoints are stored under a fixed
 * sentinel owner so {@code (owner, name)} can be a plain NOT-NULL primary key (a nullable primary
 * key is not portable across SQL backends). Upserts are a dialect-neutral delete-then-insert inside
 * a transaction rather than backend-specific {@code MERGE}/{@code ON CONFLICT}.
 */
final class JdbcWaypointDao implements WaypointDao {

  /** Sentinel owner for global (server-wide) waypoints; the all-zero UUID never names a player. */
  private static final String GLOBAL_OWNER = new UUID(0L, 0L).toString();

  private static final String INSERT =
      "INSERT INTO odyssey_waypoint (owner, name, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)";
  private static final String DELETE =
      "DELETE FROM odyssey_waypoint WHERE owner = ? AND name = ?";
  private static final String SELECT_ONE =
      "SELECT owner, name, world, x, y, z FROM odyssey_waypoint WHERE owner = ? AND name = ?";
  private static final String SELECT_BY_OWNER =
      "SELECT owner, name, world, x, y, z FROM odyssey_waypoint WHERE owner = ? ORDER BY name";

  private final AbstractJdbcDataStore store;

  JdbcWaypointDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void put(Waypoint waypoint) {
    store.inTransaction("put waypoint", connection -> {
      try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
        delete.setString(1, ownerKey(waypoint.owner()));
        delete.setString(2, waypoint.name());
        delete.executeUpdate();
      }
      try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
        insert.setString(1, ownerKey(waypoint.owner()));
        insert.setString(2, waypoint.name());
        insert.setString(3, waypoint.world());
        insert.setInt(4, waypoint.x());
        insert.setInt(5, waypoint.y());
        insert.setInt(6, waypoint.z());
        insert.executeUpdate();
      }
      return null;
    });
  }

  @Override
  public boolean remove(Optional<UUID> owner, String name) {
    return store.inTransaction("remove waypoint", connection -> {
      try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
        delete.setString(1, ownerKey(owner));
        delete.setString(2, name);
        return delete.executeUpdate() > 0;
      }
    });
  }

  @Override
  public Optional<Waypoint> get(Optional<UUID> owner, String name) {
    return store.query("get waypoint", connection -> {
      try (PreparedStatement select = connection.prepareStatement(SELECT_ONE)) {
        select.setString(1, ownerKey(owner));
        select.setString(2, name);
        try (ResultSet rows = select.executeQuery()) {
          return rows.next() ? Optional.of(read(rows)) : Optional.<Waypoint>empty();
        }
      }
    });
  }

  @Override
  public List<Waypoint> ownedBy(UUID owner) {
    return selectByOwner(owner.toString());
  }

  @Override
  public List<Waypoint> global() {
    return selectByOwner(GLOBAL_OWNER);
  }

  private List<Waypoint> selectByOwner(String ownerKey) {
    return store.query("list waypoints", connection -> {
      try (PreparedStatement select = connection.prepareStatement(SELECT_BY_OWNER)) {
        select.setString(1, ownerKey);
        try (ResultSet rows = select.executeQuery()) {
          List<Waypoint> result = new ArrayList<>();
          while (rows.next()) {
            result.add(read(rows));
          }
          return result;
        }
      }
    });
  }

  private static Waypoint read(ResultSet rows) throws SQLException {
    String ownerKey = rows.getString("owner");
    Optional<UUID> owner = GLOBAL_OWNER.equals(ownerKey)
        ? Optional.empty()
        : Optional.of(UUID.fromString(ownerKey));
    return new Waypoint(
        owner,
        rows.getString("name"),
        rows.getString("world"),
        rows.getInt("x"),
        rows.getInt("y"),
        rows.getInt("z"));
  }

  private static String ownerKey(Optional<UUID> owner) {
    return owner.map(UUID::toString).orElse(GLOBAL_OWNER);
  }
}
