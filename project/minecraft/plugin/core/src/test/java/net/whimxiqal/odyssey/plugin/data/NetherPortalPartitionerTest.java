/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the destination partition: a source portal's entry cells are grouped by which known
 * destination portal they scale-and-link to.
 */
class NetherPortalPartitionerTest {

  // A nether portal 4 wide along X (thin in Z), y 64..67, at z=0. Nether -> overworld factor 8.
  private static final PortalRegion SOURCE = new PortalRegion("nether", 0, 64, 0, 3, 67, 0);
  private static final double FACTOR = 8.0;
  private static final double COST = 5.0;

  @Test
  void noCandidates_yieldsNoLinks() {
    assertTrue(NetherPortalPartitioner.partition(SOURCE, List.of(), FACTOR, COST).isEmpty());
  }

  @Test
  void oneCandidate_coversTheWholePortal() {
    // Scaled source spans overworld x≈4..28; a single portal near the middle wins every column.
    PortalRegion dest = new PortalRegion("world", 16, 63, 4, 16, 66, 4);
    List<PortalLink> links = NetherPortalPartitioner.partition(SOURCE, List.of(dest), FACTOR, COST);
    assertEquals(1, links.size());
    PortalLink link = links.getFirst();
    assertSame(dest, link.dest());
    assertEquals(0, link.subRegion().minX());
    assertEquals(3, link.subRegion().maxX());
    assertEquals(COST, link.cost());
  }

  @Test
  void twoCandidates_splitIntoContiguousSubRegions() {
    // Columns scale to overworld x = 4,12,20,28 (z≈4). A near x=4 wins 0,1; B near x=28 wins 2,3.
    PortalRegion a = new PortalRegion("world", 4, 63, 4, 4, 66, 4);
    PortalRegion b = new PortalRegion("world", 28, 63, 4, 28, 66, 4);
    List<PortalLink> links = NetherPortalPartitioner.partition(SOURCE, List.of(a, b), FACTOR, COST);
    assertEquals(2, links.size());

    PortalLink first = links.get(0);
    assertSame(a, first.dest());
    assertEquals(0, first.subRegion().minX());
    assertEquals(1, first.subRegion().maxX());

    PortalLink second = links.get(1);
    assertSame(b, second.dest());
    assertEquals(2, second.subRegion().minX());
    assertEquals(3, second.subRegion().maxX());
  }
}
