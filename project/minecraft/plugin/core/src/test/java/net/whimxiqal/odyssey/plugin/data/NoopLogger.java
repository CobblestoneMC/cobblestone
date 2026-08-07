/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import net.whimxiqal.odyssey.OdysseyLogger;

/** A logger that swallows everything; keeps DataStore tests quiet. */
final class NoopLogger implements OdysseyLogger {

  @Override
  public void trace(String message, Object... args) {
  }

  @Override
  public void debug(String message, Object... args) {
  }

  @Override
  public void info(String message, Object... args) {
  }

  @Override
  public void warn(String message, Object... args) {
  }

  @Override
  public void error(String message, Throwable throwable, Object... args) {
  }
}
