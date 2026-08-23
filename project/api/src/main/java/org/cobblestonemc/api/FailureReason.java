/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

/** Why a search failed to produce a path. */
public enum FailureReason {

  /** No sequence of transitions connects the origin to the destination's domain(s). */
  NO_ROUTE,

  /** A route exists at the graph level, but the destination could not actually be reached. */
  DESTINATION_UNREACHABLE,

  /** A configured limit (cells visited, etc.) was exceeded before a path was found. */
  LIMIT_EXCEEDED,

  /** The search was cancelled (e.g. the player logged off). */
  CANCELLED,

  /** The search exceeded its wall-clock budget. */
  TIMED_OUT,
}
