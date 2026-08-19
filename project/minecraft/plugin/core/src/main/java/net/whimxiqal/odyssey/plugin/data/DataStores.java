/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import java.nio.file.Path;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.plugin.data.jdbc.H2DataStore;

/** Constructs the configured {@link DataStore}. Platform plugins call this at enable. */
public final class DataStores {

  private DataStores() {}

  /**
   * Creates (but does not {@link DataStore#init() open}) the store for a backend.
   *
   * @param backend the selected backend
   * @param file the database file location (in the plugin's data folder)
   * @param logger the logger for diagnostics
   * @return the store
   */
  public static DataStore create(DataBackend backend, Path file, OdysseyLogger logger) {
    return switch (backend) {
      case H2 -> new H2DataStore(file, logger);
    };
  }
}
