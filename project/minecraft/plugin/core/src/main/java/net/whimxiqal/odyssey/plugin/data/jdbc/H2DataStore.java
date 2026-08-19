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
 * An H2-backed {@link net.whimxiqal.odyssey.plugin.data.DataStore}, stored in a single embedded
 * file (H2 appends its own {@code .mv.db} suffix). An alternative embedded backend to SQLite; the
 * {@code h2} driver is downloaded at runtime by the platform plugin's library loader.
 */
public final class H2DataStore extends AbstractJdbcDataStore {

  private static final String DRIVER = "org.h2.Driver";

  /**
   * Creates a store backed by the given file (created if absent).
   *
   * @param file the database file (without the {@code .mv.db} suffix H2 adds)
   * @param logger the logger for diagnostics
   */
  public H2DataStore(Path file, OdysseyLogger logger) {
    super("jdbc:h2:file:" + file.toAbsolutePath(), logger);
  }

  @Override
  String driver() {
    return DRIVER;
  }

  @Override
  protected void loadDriver() throws ClassNotFoundException {
    Class.forName(DRIVER);
  }
}
