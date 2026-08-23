/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.cobblestonemc.plugin.data.Location;
import org.cobblestonemc.plugin.data.LocationDao;

/**
 * A {@link LocationDao} over the shared JDBC connection. Global locations are stored under a fixed
 * sentinel owner so {@code (owner, name)} can be a plain NOT-NULL primary key (a nullable primary
 * key is not portable across SQL backends). Upserts are a dialect-neutral delete-then-insert inside
 * a transaction rather than backend-specific {@code MERGE}/{@code ON CONFLICT}.
 */
final class JdbcLocationDao implements LocationDao {

  /** Sentinel owner for global (server-wide) locations; the all-zero UUID never names a player. */
  private static final String GLOBAL_OWNER = new UUID(0L, 0L).toString();

  private static final String INSERT =
      "INSERT INTO cobblestone_location (owner, name, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)";
  private static final String DELETE =
      "DELETE FROM cobblestone_location WHERE owner = ? AND name = ?";
  private static final String SELECT_ONE =
      "SELECT owner, name, world, x, y, z FROM cobblestone_location WHERE owner = ? AND name = ?";
  private static final String SELECT_BY_OWNER =
      "SELECT owner, name, world, x, y, z FROM cobblestone_location WHERE owner = ? ORDER BY name";

  private final AbstractJdbcDataStore store;

  JdbcLocationDao(AbstractJdbcDataStore store) {
    this.store = store;
  }

  @Override
  public void put(Location location) {
    store.inTransaction(
        "put location",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
            delete.setString(1, ownerKey(location.owner()));
            delete.setString(2, location.name());
            delete.executeUpdate();
          }
          try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
            insert.setString(1, ownerKey(location.owner()));
            insert.setString(2, location.name());
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
  public boolean remove(Optional<UUID> owner, String name) {
    return store.inTransaction(
        "remove location",
        connection -> {
          try (PreparedStatement delete = connection.prepareStatement(DELETE)) {
            delete.setString(1, ownerKey(owner));
            delete.setString(2, name);
            return delete.executeUpdate() > 0;
          }
        });
  }

  @Override
  public Optional<Location> get(Optional<UUID> owner, String name) {
    return store.query(
        "get location",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT_ONE)) {
            select.setString(1, ownerKey(owner));
            select.setString(2, name);
            try (ResultSet rows = select.executeQuery()) {
              return rows.next() ? Optional.of(read(rows)) : Optional.<Location>empty();
            }
          }
        });
  }

  @Override
  public List<Location> ownedBy(UUID owner) {
    return selectByOwner(owner.toString());
  }

  @Override
  public List<Location> global() {
    return selectByOwner(GLOBAL_OWNER);
  }

  private List<Location> selectByOwner(String ownerKey) {
    return store.query(
        "list locations",
        connection -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT_BY_OWNER)) {
            select.setString(1, ownerKey);
            try (ResultSet rows = select.executeQuery()) {
              List<Location> result = new ArrayList<>();
              while (rows.next()) {
                result.add(read(rows));
              }
              return result;
            }
          }
        });
  }

  private static Location read(ResultSet rows) throws SQLException {
    String ownerKey = rows.getString("owner");
    Optional<UUID> owner =
        GLOBAL_OWNER.equals(ownerKey) ? Optional.empty() : Optional.of(UUID.fromString(ownerKey));
    return new Location(
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
