/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes a source portal's destination partition the way Minecraft links portals: scale each
 * entry cell's horizontal position by the inter-world coordinate ratio and link it to the nearest
 * known portal in the destination world (within the vanilla search radius). Because a vertical
 * portal is thin in one horizontal axis, the partition is a set of contiguous intervals along its
 * wide axis, each a {@link PortalLink} sub-region to one destination portal.
 *
 * <p>This is a platform-neutral estimate of Minecraft's internal linking, exact once the cache of
 * destination portals is complete and self-correcting as more are learned. Cells with no
 * destination in range contribute no link.
 */
public final class NetherPortalPartitioner {

  /** Vanilla portal search radius, in blocks (destination space). */
  public static final double DEFAULT_SEARCH_RADIUS = 128.0;

  private NetherPortalPartitioner() {}

  /**
   * Partitions {@code source} over the candidate destination portals.
   *
   * @param source the source portal
   * @param candidates known portals in the destination world
   * @param factor {@code destCoord = sourceCoord * factor} (source scale / dest scale, e.g. 8
   *     nether → overworld, 1/8 the other way)
   * @param cost the traversal cost to stamp on each link
   * @return one link per contiguous entry interval that has a destination
   */
  public static List<PortalLink> partition(
      PortalRegion source, List<PortalRegion> candidates, double factor, double cost) {
    List<PortalLink> links = new ArrayList<>();
    if (candidates.isEmpty()) {
      return links;
    }
    boolean wideZ = source.maxZ() > source.minZ() && source.maxX() == source.minX();
    int lo = wideZ ? source.minZ() : source.minX();
    int hi = wideZ ? source.maxZ() : source.maxX();

    PortalRegion runDest = null;
    int runStart = lo;
    for (int c = lo; c <= hi; c++) {
      double srcX = (wideZ ? source.minX() : c) + 0.5;
      double srcZ = (wideZ ? c : source.minZ()) + 0.5;
      PortalRegion nearest = nearest(candidates, srcX * factor, srcZ * factor);
      boolean sameRun = (runDest == null && nearest == null) || sameDest(runDest, nearest);
      if (!sameRun) {
        emit(links, source, runDest, wideZ, runStart, c - 1, cost);
        runDest = nearest;
        runStart = c;
      }
    }
    emit(links, source, runDest, wideZ, runStart, hi, cost);
    return links;
  }

  /** The nearest candidate to a destination-space point, or {@code null} if none within radius. */
  private static PortalRegion nearest(List<PortalRegion> candidates, double x, double z) {
    PortalRegion best = null;
    double bestDistance = DEFAULT_SEARCH_RADIUS;
    for (PortalRegion candidate : candidates) {
      double distance = candidate.horizontalDistanceTo(x, z);
      if (distance <= bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    return best;
  }

  private static boolean sameDest(PortalRegion a, PortalRegion b) {
    return a != null && b != null && a.sameAnchor(b);
  }

  /**
   * Emits a link for the interval {@code [runStart, runEnd]} along the wide axis, if it has a dest.
   */
  private static void emit(
      List<PortalLink> links,
      PortalRegion source,
      PortalRegion dest,
      boolean wideZ,
      int runStart,
      int runEnd,
      double cost) {
    if (dest == null) {
      return;
    }
    PortalRegion subRegion =
        wideZ
            ? new PortalRegion(
                source.world(),
                source.minX(),
                source.minY(),
                runStart,
                source.maxX(),
                source.maxY(),
                runEnd)
            : new PortalRegion(
                source.world(),
                runStart,
                source.minY(),
                source.minZ(),
                runEnd,
                source.maxY(),
                source.maxZ());
    links.add(new PortalLink(source, subRegion, dest, cost));
  }
}
