/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.plugin.data.DataStore;
import net.whimxiqal.odyssey.plugin.data.DataStoreException;
import net.whimxiqal.odyssey.plugin.data.GatewayDao;
import net.whimxiqal.odyssey.plugin.data.PortalCacheDao;
import net.whimxiqal.odyssey.plugin.data.PortalLinkDao;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import net.whimxiqal.odyssey.plugin.data.WaypointDao;

/**
 * A {@link DataStore} over any JDBC backend. Shared by the SQL backends (SQLite, H2, and later
 * MySQL/PostgreSQL); each subclass supplies only its JDBC URL and driver class.
 *
 * <p>The store holds a single {@link Connection} guarded by a lock. Waypoint and (later) portal
 * writes are infrequent, so serializing DB access is simpler than a pool and keeps SQLite — which
 * serializes writes anyway — honest; a connection pool can arrive with the high-concurrency
 * backends in Phase 7. Schema is versioned by an ordered list of {@link #migrations()} statements
 * applied inside a transaction and recorded in {@code odyssey_schema_version}.
 */
public abstract class AbstractJdbcDataStore implements DataStore {

  private final String url;
  private final OdysseyLogger logger;

  /** Guards the single connection; all DAO work synchronizes on it. */
  final Object lock = new Object();

  private Connection connection;
  private WaypointDao waypointDao;
  private PortalTransitionDao portalTransitionDao;
  private PortalCacheDao portalCacheDao;
  private PortalLinkDao portalLinkDao;
  private GatewayDao gatewayDao;

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
   * Loads the ordered schema migrations from classpath resources next to this class ({@code
   * migrations/1.sql}, {@code migrations/2.sql}, …), probing sequentially until the next number is
   * absent. Each file's version is its filename number; a file may hold several {@code ;}-separated
   * statements and {@code --} comments describing it. Dialect-neutral for now; a subclass could
   * point at a backend-specific folder if a statement ever needs to differ.
   *
   * @return the statements of each migration, in version order (index 0 = version 1)
   */
  protected List<List<String>> migrations() {
    List<List<String>> migrations = new ArrayList<>();
    for (int version = 1; ; version++) {
      String resource = "migrations/" + version + ".sql";
      try (InputStream in = AbstractJdbcDataStore.class.getResourceAsStream(resource)) {
        if (in == null) {
          break;
        }
        migrations.add(parseStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      } catch (IOException e) {
        throw new DataStoreException("failed to read migration " + resource, e);
      }
    }
    return migrations;
  }

  /** Splits a migration file into its individual (non-empty) statements. */
  private static List<String> parseStatements(String sql) {
    List<String> statements = new ArrayList<>();
    for (String part : sql.split(";")) {
      String trimmed = part.strip();
      if (!trimmed.isEmpty()) {
        statements.add(trimmed);
      }
    }
    return statements;
  }

  @Override
  public void init() {
    try {
      loadDriver();
      this.connection = DriverManager.getConnection(url);
      migrate();
      this.waypointDao = new JdbcWaypointDao(this);
      this.portalTransitionDao = new JdbcPortalTransitionDao(this);
      this.portalCacheDao = new JdbcPortalCacheDao(this);
      this.portalLinkDao = new JdbcPortalLinkDao(this);
      this.gatewayDao = new JdbcGatewayDao(this);
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
  public PortalCacheDao netherPortals() {
    if (portalCacheDao == null) {
      throw new IllegalStateException("DataStore.init() has not been called");
    }
    return portalCacheDao;
  }

  @Override
  public PortalLinkDao netherPortalLinks() {
    if (portalLinkDao == null) {
      throw new IllegalStateException("DataStore.init() has not been called");
    }
    return portalLinkDao;
  }

  @Override
  public GatewayDao gateways() {
    if (gatewayDao == null) {
      throw new IllegalStateException("DataStore.init() has not been called");
    }
    return gatewayDao;
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
      int start = Math.max(current, 0);
      List<List<String>> migrations = migrations();
      connection.setAutoCommit(false);
      try {
        int applied = start;
        for (int version = start + 1; version <= migrations.size(); version++) {
          for (String sql : migrations.get(version - 1)) {
            try (Statement statement = connection.createStatement()) {
              statement.executeUpdate(sql);
            }
          }
          applied = version;
        }
        writeVersion(applied, fresh);
        connection.commit();
        // Announce a genuine upgrade of an existing store, but stay quiet on first-time setup.
        if (!fresh && applied > start) {
          logger.info("Migrated Odyssey data store schema from v{} to v{}.", start, applied);
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
    String sql =
        insert
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
