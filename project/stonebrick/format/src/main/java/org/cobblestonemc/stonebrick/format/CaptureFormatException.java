/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import java.io.IOException;

/**
 * A capture file could not be decoded: wrong magic, a version this codec does not know, a checksum
 * mismatch, or a structurally impossible value.
 *
 * <p>Always thrown rather than absorbed. A benchmark that quietly substituted air for a corrupt
 * cube would report a number, and the number would be wrong in a way nothing downstream could
 * detect.
 */
public class CaptureFormatException extends IOException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with the given message.
   *
   * @param message what was wrong with the file
   */
  public CaptureFormatException(String message) {
    super(message);
  }

  /**
   * Creates an exception with the given message and cause.
   *
   * @param message what was wrong with the file
   * @param cause the underlying failure
   */
  public CaptureFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
