/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

/** What the trail sees at a block position. */
public enum TrailBlock {
  /** Nothing the trail must keep clear of (air, grass, water, ...). */
  OPEN,
  /** A solid block the trail must not cut through. */
  SOLID,
  /**
   * Not immediately readable (chunk not loaded, or owned by another region thread): the trail
   * neither smooths nor draws here.
   */
  UNLOADED
}
