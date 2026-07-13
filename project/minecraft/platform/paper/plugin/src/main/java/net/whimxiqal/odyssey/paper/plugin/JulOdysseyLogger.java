/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.whimxiqal.odyssey.api.OdysseyLogger;

/**
 * Adapts the plugin's {@link java.util.logging.Logger} to Odyssey's {@link OdysseyLogger} seam,
 * translating SLF4J-style {@code {}} placeholders into the interpolated message.
 */
final class JulOdysseyLogger implements OdysseyLogger {

  private final Logger logger;

  JulOdysseyLogger(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void trace(String message, Object... args) {
    logger.finer(format(message, args));
  }

  @Override
  public void debug(String message, Object... args) {
    logger.fine(format(message, args));
  }

  @Override
  public void info(String message, Object... args) {
    logger.info(format(message, args));
  }

  @Override
  public void warn(String message, Object... args) {
    logger.warning(format(message, args));
  }

  @Override
  public void error(String message, Throwable throwable, Object... args) {
    logger.log(Level.SEVERE, format(message, args), throwable);
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
      if (arg < args.length && i + 1 < message.length()
          && message.charAt(i) == '{' && message.charAt(i + 1) == '}') {
        out.append(String.valueOf(args[arg++]));
        i += 2;
      } else {
        out.append(message.charAt(i++));
      }
    }
    return out.toString();
  }
}
