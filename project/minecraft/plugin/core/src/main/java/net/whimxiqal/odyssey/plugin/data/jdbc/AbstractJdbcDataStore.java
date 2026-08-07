/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.plugin.data.DataStore;
import net.whimxiqal.odyssey.plugin.data.DataStoreException;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;

/**
 * A {@link DataStore} over any JDBC backend. Shared by the SQL backends (SQLite, H2, and later
 * MySQL/PostgreSQL); each subclass supplies only its JDBC URL and driver class.
 *
 * <p>The store holds a single {@link Connection} guarded by a lock. Waypoint and (later) portal
 * writes are infrequent, so serializing DB access is simpler than a pool and keeps SQLite — which
 * serializes writes anyway — honest; a connection pool can arrive with the high-concurrency backends
 * in Phase 7. Schema is versioned by an ordered list of {@link #migrations()} statements applied
 * inside a transaction and recorded in {@code odyssey_schema_version}.
 */
public abstract class AbstractJdbcDataStore implements DataStore {

  private final String url;
  private final OdysseyLogger logger;
  /** Guards the single connection; all DAO work synchronizes on it. */
  final Object lock = new Object();

  private Connection connection;
  private WaypointDao waypointDao;
  private PortalTransitionDao portalTransitionDao;

  /**
   * Creates a store.
   *
   * @param url the JDBC connection URL
   * @param logger the logger for diagnostics
   */
  protected AbstractJdbcDataStore(String url, OdysseyLogger logger) {
    this.url = url;
    this.logger = logger;
  }

  /**
   * Loads (and thereby registers) this backend's JDBC driver before the first connection. Modern
   * drivers auto-register via {@code ServiceLoader}, but an explicit load is robust when the driver
   * is downloaded into the plugin's own classloader at runtime.
   *
   * @throws ClassNotFoundException if the driver class is not on the classpath
   */
  protected abstract void loadDriver() throws ClassNotFoundException;

  /**
   * Returns the ordered schema migrations. Migration {@code n} is {@code migrations().get(n - 1)};
   * on init, every migration past the recorded version is applied in order. The default schema is
   * dialect-neutral; a subclass may override to tweak DDL for its backend.
   *
   * @return the ordered list of migration statements
   */
  protected List<String> migrations() {
    return List.of(
        "CREATE TABLE odyssey_waypoint ("
            + "owner CHAR(36) NOT NULL, "
            + "name VARCHAR(64) NOT NULL, "
            + "world VARCHAR(255) NOT NULL, "
            + "x INTEGER NOT NULL, "
            + "y INTEGER NOT NULL, "
            + "z INTEGER NOT NULL, "
            + "PRIMARY KEY (owner, name))",
        "CREATE TABLE odyssey_portal_transition ("
            + "from_world VARCHAR(255) NOT NULL, "
            + "min_x INTEGER NOT NULL, min_y INTEGER NOT NULL, min_z INTEGER NOT NULL, "
            + "max_x INTEGER NOT NULL, max_y INTEGER NOT NULL, max_z INTEGER NOT NULL, "
            + "to_world VARCHAR(255) NOT NULL, "
            + "to_x INTEGER NOT NULL, to_y INTEGER NOT NULL, to_z INTEGER NOT NULL, "
            + "cost DOUBLE NOT NULL)");
  }

  @Override
  public void init() {
    try {
      loadDriver();
      this.connection = DriverManager.getConnection(url);
      migrate();
      this.waypointDao = new JdbcWaypointDao(this);
      this.portalTransitionDao = new JdbcPortalTransitionDao(this);
    } catch (ClassNotFoundException | SQLException e) {
      throw new DataStoreException("failed to open data store (" + url + ")", e);
    }
  }

  @Override
  public WaypointDao waypoints() {
    if (waypointDao == null) {
      throw new IllegalStateException("DataStore.init() has not been called");
    }
    return waypointDao;
  }

  @Override
  public PortalTransitionDao portalTransitions() {
    if (portalTransitionDao == null) {
      throw new IllegalStateException("DataStore.init() has not been called");
    }
    return portalTransitionDao;
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (connection == null) {
        return;
      }
      try {
        connection.close();
      } catch (SQLException e) {
        logger.warn("Failed to cleanly close data store ({}): {}", url, e.getMessage());
      } finally {
        connection = null;
      }
    }
  }

  private void migrate() throws SQLException {
    synchronized (lock) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS odyssey_schema_version (version INTEGER NOT NULL)");
      }
      int current = readVersion();
      boolean fresh = current < 0;
      List<String> migrations = migrations();
      connection.setAutoCommit(false);
      try {
        int applied = Math.max(current, 0);
        for (int version = applied + 1; version <= migrations.size(); version++) {
          try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(migrations.get(version - 1));
          }
          applied = version;
        }
        writeVersion(applied, fresh);
        connection.commit();
        if (applied != Math.max(current, 0)) {
          logger.info("Data store schema migrated to version {}.", applied);
        }
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  private int readVersion() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT version FROM odyssey_schema_version")) {
      return rows.next() ? rows.getInt(1) : -1;
    }
  }

  private void writeVersion(int version, boolean insert) throws SQLException {
    String sql = insert
        ? "INSERT INTO odyssey_schema_version (version) VALUES (?)"
        : "UPDATE odyssey_schema_version SET version = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, version);
      statement.executeUpdate();
    }
  }

  /** A unit of JDBC work returning a result. */
  @FunctionalInterface
  interface SqlWork<R> {
    R run(Connection connection) throws SQLException;
  }

  /** Runs read-only work under the connection lock, wrapping failures uniformly. */
  <R> R query(String what, SqlWork<R> work) {
    synchronized (lock) {
      try {
        return work.run(connection);
      } catch (SQLException e) {
        throw new DataStoreException(what, e);
      }
    }
  }

  /** Runs write work as a single transaction under the connection lock, rolling back on failure. */
  <R> R inTransaction(String what, SqlWork<R> work) {
    synchronized (lock) {
      try {
        connection.setAutoCommit(false);
        try {
          R result = work.run(connection);
          connection.commit();
          return result;
        } catch (SQLException e) {
          connection.rollback();
          throw new DataStoreException(what, e);
        } finally {
          connection.setAutoCommit(true);
        }
      } catch (SQLException e) {
        throw new DataStoreException(what, e);
      }
    }
  }
}
