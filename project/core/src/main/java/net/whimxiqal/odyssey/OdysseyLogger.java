/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

/**
 * A minimal, framework-agnostic logging seam, injected so the core depends on no logging library.
 *
 * <p>The algorithms log heavily at {@code trace} (candidate pops, parks, recalcs) to make unit
 * tests and live servers diagnosable. Logger messages are developer-facing and are never
 * internationalized. Placeholders use the SLF4J {@code {}} style.
 */
public interface OdysseyLogger {

  /**
   * Logs at trace level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  void trace(String message, Object... args);

  /**
   * Logs at debug level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  void debug(String message, Object... args);

  /**
   * Logs at info level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  void info(String message, Object... args);

  /**
   * Logs at warn level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  void warn(String message, Object... args);

  /**
   * Logs at error level with an associated throwable.
   *
   * @param message the message, with {@code {}} placeholders
   * @param throwable the error
   * @param args the placeholder arguments
   */
  void error(String message, Throwable throwable, Object... args);
}
