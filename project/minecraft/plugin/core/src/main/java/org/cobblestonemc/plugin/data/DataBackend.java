/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

/**
 * The persistence backend an admin selects in config. Only the embedded, zero-setup engines ship in
 * Phase 6b; the networked SQL backends and MongoDB join this enum in Phase 7.
 */
public enum DataBackend {

  /** H2 — an alternative embedded engine. */
  H2
}
