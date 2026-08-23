/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * A minimal, framework-agnostic logging seam, injected so the core depends on no logging library.
 *
 * <p>The algorithms log heavily at {@code trace} (candidate pops, parks, recalcs) to make unit
 * tests and live servers diagnosable. Logger messages are developer-facing and are never
 * internationalized. Placeholders use the SLF4J {@code {}} style.
 */
public abstract class CobblestoneLogger {

  /**
   * Logs at trace level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  public abstract void trace(String message, Object... args);

  /**
   * Logs at debug level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  public abstract void debug(String message, Object... args);

  /**
   * Logs at info level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  public abstract void info(String message, Object... args);

  /**
   * Logs at warn level.
   *
   * @param message the message, with {@code {}} placeholders
   * @param args the placeholder arguments
   */
  public abstract void warn(String message, Object... args);

  /**
   * Logs at error level with an associated throwable.
   *
   * @param message the message, with {@code {}} placeholders
   * @param throwable the error
   * @param args the placeholder arguments
   */
  public abstract void error(String message, Throwable throwable, Object... args);

  /** Replaces each {@code {}} in order with the string form of the next argument. */
  protected static String format(String message, Object[] args) {
    if (args == null || args.length == 0) {
      return message;
    }
    StringBuilder out = new StringBuilder(message.length() + 16);
    int arg = 0;
    int i = 0;
    while (i < message.length()) {
      if (arg < args.length
          && i + 1 < message.length()
          && message.charAt(i) == '{'
          && message.charAt(i + 1) == '}') {
        out.append(String.valueOf(args[arg++]));
        i += 2;
      } else {
        out.append(message.charAt(i++));
      }
    }
    return out.toString();
  }
}
