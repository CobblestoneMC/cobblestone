/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import org.apache.logging.log4j.Logger;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.LogLevel;

/**
 * Adapts the plugin's Log4j {@link Logger} to Cobblestone's {@link CobblestoneLogger} seam,
 * interpolating SLF4J-style {@code {}} placeholders. Verbosity is gated by an admin-configurable
 * {@link LogLevel} threshold; {@code trace}/{@code debug} are emitted at {@code info} with a prefix
 * (only when the threshold allows) so they reach the console regardless of the server's Log4j
 * configuration.
 */
final class Log4JCobblestoneLogger extends CobblestoneLogger {

  private final Logger logger;
  private volatile LogLevel threshold = LogLevel.INFO;

  Log4JCobblestoneLogger(Logger logger) {
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
      logger.trace("[TRACE] {}", format(message, args));
    }
  }

  @Override
  public void debug(String message, Object... args) {
    if (enabled(LogLevel.DEBUG)) {
      logger.info("[DEBUG] {}", format(message, args));
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
}
