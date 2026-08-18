/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import java.util.List;

/**
 * Persistence for nether {@link PortalLink}s — the per-source-portal destination partition. Writes
 * are by whole source portal (the partition is recomputed and replaced on each observation), so a
 * re-walk <b>updates</b> rather than piling up duplicate rows. Thread-safe.
 */
public interface PortalLinkDao {

  /**
   * Replaces all links whose source is {@code source} with {@code links} (the freshly-computed
   * partition). Passing an empty list clears the source's links.
   *
   * @param source the source portal whose partition is being replaced
   * @param links the new partition
   */
  void replaceForSource(PortalRegion source, List<PortalLink> links);

  /**
   * Every recorded link.
   *
   * @return the links (never {@code null})
   */
  List<PortalLink> all();

  /**
   * Removes every link that references {@code portal} as its source or destination (culled when the
   * portal no longer exists).
   *
   * @param portal the portal being culled
   * @return how many links were removed
   */
  int removeReferencing(PortalRegion portal);

  /**
   * Removes every link.
   *
   * @return how many were removed
   */
  int clear();
}
