/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

/**
 * The verbosity threshold for {@link OdysseyLogger} output, admin-selectable in config. A message
 * is emitted when its level is at or above the configured threshold; the constants are ordered from
 * most to least verbose.
 */
public enum LogLevel {

  /** Everything, including per-candidate search detail. */
  TRACE,

  /** Diagnostic detail (per-search summaries). */
  DEBUG,

  /** Normal lifecycle messages. */
  INFO,

  /** Recoverable problems. */
  WARN,

  /** Errors only. */
  ERROR
}
