/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import net.whimxiqal.odyssey.LogLevel;
import net.whimxiqal.odyssey.OdysseyLogger;
import org.apache.logging.log4j.Logger;

/**
 * Adapts the plugin's Log4j {@link Logger} to Odyssey's {@link OdysseyLogger} seam, interpolating
 * SLF4J-style {@code {}} placeholders. Verbosity is gated by an admin-configurable {@link LogLevel}
 * threshold; {@code trace}/{@code debug} are emitted at {@code info} with a prefix (only when the
 * threshold allows) so they reach the console regardless of the server's Log4j configuration.
 */
final class Log4jOdysseyLogger implements OdysseyLogger {

  private final Logger logger;
  private volatile LogLevel threshold = LogLevel.INFO;

  Log4jOdysseyLogger(Logger logger) {
    this.logger = logger;
  }

  /**
   * Sets the verbosity threshold (call at enable and after a config reload).
   *
   * @param level the minimum level to emit
   */
  void setLevel(LogLevel level) {
    this.threshold = level;
  }

  private boolean enabled(LogLevel level) {
    return level.ordinal() >= threshold.ordinal();
  }

  @Override
  public void trace(String message, Object... args) {
    if (enabled(LogLevel.TRACE)) {
      logger.info("[TRACE] " + format(message, args));
    }
  }

  @Override
  public void debug(String message, Object... args) {
    if (enabled(LogLevel.DEBUG)) {
      logger.info("[DEBUG] " + format(message, args));
    }
  }

  @Override
  public void info(String message, Object... args) {
    if (enabled(LogLevel.INFO)) {
      logger.info(format(message, args));
    }
  }

  @Override
  public void warn(String message, Object... args) {
    if (enabled(LogLevel.WARN)) {
      logger.warn(format(message, args));
    }
  }

  @Override
  public void error(String message, Throwable throwable, Object... args) {
    if (enabled(LogLevel.ERROR)) {
      logger.error(format(message, args), throwable);
    }
  }

  /** Replaces each {@code {}} in order with the string form of the next argument. */
  private static String format(String message, Object[] args) {
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
        out.append(args[arg++]);
        i += 2;
      } else {
        out.append(message.charAt(i++));
      }
    }
    return out.toString();
  }
}
