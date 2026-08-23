/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.Collection;
import java.util.List;
import org.cobblestonemc.api.Destination;

/**
 * A {@link Destination} of exactly one region — the overwhelmingly common case (navigate to one
 * place). Pair with {@link CellRegion} for a precise single-cell target.
 *
 * @param <R> the region type
 * @param region the sole destination region
 */
public record SingleDestination<R>(R region) implements Destination<R> {

  @Override
  public Collection<R> regions() {
    return List.of(region);
  }
}
