/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

public class ScopedCobblestoneLogger extends CobblestoneLogger {

  private final CobblestoneLogger delegate;
  private final String scope;

  public ScopedCobblestoneLogger(CobblestoneLogger delegate, String scope) {
    this.delegate = delegate;
    this.scope = scope;
  }

  @Override
  public void trace(String message, Object... args) {
    delegate.trace("[" + scope + "] " + message, args);
  }

  @Override
  public void debug(String message, Object... args) {
    delegate.debug("[" + scope + "] " + message, args);
  }

  @Override
  public void info(String message, Object... args) {
    delegate.info("[" + scope + "] " + message, args);
  }

  @Override
  public void warn(String message, Object... args) {
    delegate.warn("[" + scope + "] " + message, args);
  }

  @Override
  public void error(String message, Throwable throwable, Object... args) {
    delegate.error("[" + scope + "] " + message, throwable, args);
  }
}
