/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

public interface WorldRegion<W, V> {

  W world();

  boolean contains(V location);

  V nearestBoundaryLocation(V location);
}
