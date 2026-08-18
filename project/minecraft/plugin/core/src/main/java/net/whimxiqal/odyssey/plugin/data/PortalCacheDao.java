/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import java.util.List;

/**
 * Persistence for the nether-portal cache: every portal region Odyssey has observed, keyed by
 * anchor. Used to compute which destination a given entry cell links to (the nearest known portal
 * in the destination world) and to cull links when a portal is gone. Thread-safe.
 */
public interface PortalCacheDao {

  /**
   * Records (or refreshes the extent of) a portal, keyed by its anchor. Idempotent.
   *
   * @param portal the portal region
   */
  void upsert(PortalRegion portal);

  /**
   * Every cached portal.
   *
   * @return the portals (never {@code null})
   */
  List<PortalRegion> all();

  /**
   * The cached portals in one world (the destination candidates for a partition).
   *
   * @param world the world's namespaced key
   * @return the portals in that world
   */
  List<PortalRegion> inWorld(String world);

  /**
   * Removes a cached portal by its anchor.
   *
   * @param portal the portal to forget
   */
  void remove(PortalRegion portal);

  /**
   * Removes every cached portal.
   *
   * @return how many were removed
   */
  int clear();
}
