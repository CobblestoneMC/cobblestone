/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

/** A handle to a scheduled repeating task, allowing it to be cancelled. */
@FunctionalInterface
public interface ScheduledTaskHandle {

  /** Cancels the task; no further runs occur. Idempotent. */
  void cancel();
}
