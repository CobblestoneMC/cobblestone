/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import java.util.Collection;

/**
 * One logical destination, expressed as one or more {@link DomainRegion}s.
 *
 * <p>The regions may span several domain instances / endpoints (e.g. "the closest town"); the
 * search models them as a virtual super-sink and finds the cheapest reachable one.
 *
 * @param <D> the domain type
 */
public interface Destination<D extends Domain> {

  /**
   * Returns the regions any of which satisfies this destination.
   *
   * @return the destination regions
   */
  Collection<DomainRegion<D>> regions();
}
