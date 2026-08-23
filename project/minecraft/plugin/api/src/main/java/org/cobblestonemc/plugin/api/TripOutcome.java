/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import org.cobblestonemc.api.FailureReason;

/**
 * The result of asking a trip service to start a trip. Platform-neutral, so the shared trip-start
 * flow and each platform's trip-service facade speak the same outcome type.
 */
public sealed interface TripOutcome {

  /** A trip was started. */
  record Started(int tripId, double durationSeconds) implements TripOutcome {}

  /** No route was found (or the search failed). */
  record Failed(FailureReason reason) implements TripOutcome {}

  /** The player is already at their trip limit. */
  record TripLimitReached() implements TripOutcome {}

  /** The search or trip start threw. */
  record Error(Throwable throwable) implements TripOutcome {}
}
