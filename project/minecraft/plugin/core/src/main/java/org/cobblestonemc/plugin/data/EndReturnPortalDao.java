/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.List;

/**
 * Persistence for learned {@link EndReturnPortal end-return portals}. Keyed by the portal anchor;
 * the destination is not stored (it is per-player).
 */
public interface EndReturnPortalDao {

  /**
   * Records an end-return portal, keyed by its anchor (world + minimum corner). A known portal has
   * its extent and cost updated; an unknown one is inserted.
   *
   * @param portal the end-return portal
   */
  void upsert(EndReturnPortal portal);

  /**
   * Every recorded end-return portal.
   *
   * @return the portals (never {@code null})
   */
  List<EndReturnPortal> all();

  /**
   * Removes every recorded end-return portal.
   *
   * @return how many were removed
   */
  int clear();
}
