/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import java.util.List;

/** Persistence for empirically-discovered {@link PortalTransition}s. Thread-safe. */
public interface PortalTransitionDao {

  /**
   * Records a portal transition. Idempotent: an identical transition is not stored twice.
   *
   * @param transition the transition to record
   */
  void add(PortalTransition transition);

  /**
   * Returns every recorded portal transition.
   *
   * @return the transitions (never {@code null}; empty if none)
   */
  List<PortalTransition> all();

  /**
   * Removes every recorded portal transition (for {@code /odyssey portals clear}).
   *
   * @return how many were removed
   */
  int clear();
}
