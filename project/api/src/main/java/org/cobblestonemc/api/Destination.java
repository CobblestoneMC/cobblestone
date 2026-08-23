/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

import java.util.Collection;

/**
 * One logical destination, expressed as one or more regionss.
 *
 * <p>The regions may span several domain instances / endpoints (e.g. "the closest town"); the
 * search models them as a virtual super-sink and finds the cheapest reachable one.
 *
 * @param <R> the region type
 */
public interface Destination<R> {

  /**
   * Returns the regions any of which satisfies this destination.
   *
   * @return the destination regions
   */
  Collection<R> regions();
}
