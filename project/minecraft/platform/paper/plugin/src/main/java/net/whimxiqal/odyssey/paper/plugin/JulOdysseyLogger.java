/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.whimxiqal.odyssey.LogLevel;
import net.whimxiqal.odyssey.OdysseyLogger;

/**
 * Adapts the plugin's {@link java.util.logging.Logger} to Odyssey's {@link OdysseyLogger} seam,
 * translating SLF4J-style {@code {}} placeholders into the interpolated message.
 *
 * <p>Verbosity is gated by an admin-configurable {@link LogLevel} threshold here rather than via
 * JUL levels: Paper routes plugin loggers through its own pipeline that drops {@code FINE}/{@code
 * FINER}, so {@code trace}/{@code debug} are emitted at {@code INFO} with a prefix (only when the
 * threshold allows) to guarantee they reach the console during debugging.
 */
final class JulOdysseyLogger extends OdysseyLogger {

  private final Logger logger;
  private volatile LogLevel threshold = LogLevel.INFO;

  JulOdysseyLogger(Logger logger) {
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
      logger.warning(format(message, args));
    }
  }

  @Override
  public void error(String message, Throwable throwable, Object... args) {
    if (enabled(LogLevel.ERROR)) {
      logger.log(Level.SEVERE, format(message, args), throwable);
    }
  }
}
