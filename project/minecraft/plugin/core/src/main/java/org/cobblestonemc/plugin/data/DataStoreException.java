/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

/**
 * An unchecked wrapper for a failure inside a {@link DataStore} (typically a {@code SQLException}).
 * DAO methods do not declare checked exceptions, so callers translate this into a user-facing error
 * message at the command layer rather than threading {@code SQLException} through the plugin.
 */
public class DataStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message and cause.
   *
   * @param message what operation failed
   * @param cause the underlying error
   */
  public DataStoreException(String message, Throwable cause) {
    super(message, cause);
  }

  public static class NoDriver extends DataStoreException {

    private static final long serialVersionUID = 1L;

    private final String missingDriver;

    public NoDriver(String missingDriver, Throwable cause) {
      super("Missing driver " + missingDriver, cause);
      this.missingDriver = missingDriver;
    }

    public String getMissingDriver() {
      return missingDriver;
    }
  }
}
