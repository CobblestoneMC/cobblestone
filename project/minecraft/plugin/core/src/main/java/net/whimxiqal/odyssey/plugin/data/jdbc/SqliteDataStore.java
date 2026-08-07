/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data.jdbc;

import java.nio.file.Path;
import net.whimxiqal.odyssey.OdysseyLogger;

/**
 * A SQLite-backed {@link net.whimxiqal.odyssey.plugin.data.DataStore}, stored in a single file in
 * the plugin's data folder. The default backend: zero setup, no server process. The {@code
 * sqlite-jdbc} driver is downloaded at runtime by the platform plugin's library loader.
 */
public final class SqliteDataStore extends AbstractJdbcDataStore {

  private static final String DRIVER = "org.sqlite.JDBC";

  /**
   * Creates a store backed by the given file (created if absent).
   *
   * @param file the database file
   * @param logger the logger for diagnostics
   */
  public SqliteDataStore(Path file, OdysseyLogger logger) {
    super("jdbc:sqlite:" + file.toAbsolutePath(), logger);
  }

  @Override
  protected void loadDriver() throws ClassNotFoundException {
    Class.forName(DRIVER);
  }
}
