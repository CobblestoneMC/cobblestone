/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.Cell;
import org.cobblestonemc.DomainBounds;
import org.cobblestonemc.regions.ToyWorld.Toy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RegionSectionBuilderTest {

  /** Walks ground and swims water, and cannot dig: only portals join anything for it. */
  private static final class NoDigging implements RegionAgent<Toy> {
    private final ToyWorld.Walker walker = new ToyWorld.Walker();

    /** The deepest fall onto ground it survives. */
    private final int safeFall;

    NoDigging(int safeFall) {
      this.safeFall = safeFall;
    }

    NoDigging() {
      this(3);
    }

    @Override
    public boolean usable(Toy clazz) {
      return clazz == Toy.GROUND || clazz == Toy.POOL;
    }

    @Override
    public double traverse(
        Toy clazz, double fx, double fy, double fz, double tx, double ty, double tz) {
      return walker.traverse(clazz, fx, fy, fz, tx, ty, tz);
    }

    @Override
    public double cheapest() {
      return walker.cheapest();
    }

    @Override
    public double fallback() {
      return walker.fallback();
    }

    @Override
    public double fall(Toy clazz, int depth) {
      return clazz == Toy.POOL || depth <= safeFall ? 0 : Double.POSITIVE_INFINITY;
    }
  }

  @Test
  void aCrowdedSectionNeverRunsOutOfRegionNumbers() {
    // Stone and air in a 3D checkerboard through the second section: every stone cell touches only
    // air, so each is a region of its own, 2,048 in one section.
    ToyWorld.Blocks checkerboard =
        (x, y, z) ->
            y < 16
                ? ToyWorld.Material.STONE
                : y < 32 && ((x + y + z) & 1) == 0
                    ? ToyWorld.Material.STONE
                    : ToyWorld.Material.AIR;
    RegionSection built = new RegionSectionBuilder<>(ToyWorld.spec()).build(0, 1, 0, checkerboard);

    assertNotNull(built.wideCells, "more than 255 regions: a short per cell");
    assertTrue(built.regions() > 2048);
    for (int ly = 0; ly < 16; ly++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          assertNotEquals(RegionSection.NONE, built.regionAt(x, ly, z), "no cell left a hole");
        }
      }
    }
    // Two stone cells a diagonal apart are separate regions.
    assertNotEquals(built.regionAt(0, 0, 0), built.regionAt(1, 1, 0));
  }

  @Test
  void cellsPastTheDomainsEdgeBelongToNoRegion() {
    // A domain that ends at x 7: the section at x 0..15 keeps only its first half.
    DomainBounds bounds =
        new DomainBounds(
            Integer.MIN_VALUE,
            7,
            ToyWorld.MIN_Y,
            ToyWorld.MAX_Y,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE);
    RegionSection built =
        new RegionSectionBuilder<>(new RegionSpec<>(ToyWorld.TERRAIN, bounds))
            .build(0, 0, 0, ToyWorld::hills);

    // Stone, well below the hills' ground.
    assertNotEquals(RegionSection.NONE, built.regionAt(7, 5, 3));
    assertEquals(RegionSection.NONE, built.regionAt(8, 5, 3));
  }

  @Test
  void aDomainNeedNotBoundY() {
    // The hills under an unbounded sky, and the stone under them going on forever: a walk across
    // them is priced the same as in a domain with a floor and a ceiling.
    ToyWorld.Blocks endless =
        (x, y, z) -> y < 0 ? ToyWorld.Material.STONE : ToyWorld.hills(x, y, z);
    Cell from = new Cell(40, 13, 4);
    Cell to = new Cell(2, 13, 30);
    RegionAgent<Toy> walker = new ToyWorld.Walker();

    double bounded = estimate(ToyWorld.spec(), new ToyWorld.Source(endless), walker, from, to);
    double unbounded =
        estimate(
            new RegionSpec<>(ToyWorld.TERRAIN, DomainBounds.UNBOUNDED),
            new ToyWorld.Source(endless),
            walker,
            from,
            to);

    assertEquals(bounded, unbounded, 1e-9);
  }

  private static double estimate(
      RegionSpec<ToyWorld.Blocks, Toy> spec,
      ToyWorld.Source source,
      RegionAgent<Toy> agent,
      Cell from,
      Cell to) {
    RegionGraph<ToyWorld.Blocks, Toy> graph = new RegionGraph<>(RegionStore.inMemory(spec), source);
    return new RegionSearch<>(graph, agent, from, to::equals, cell -> to).costToGoal(from);
  }

  /**
   * Ground stepping up {@code rise} blocks at {@code x = edge} from {@code base}: a step at 1, a
   * ledge at 3.
   *
   * @return the estimate from {@code from} to {@code to}
   */
  private static double across(
      int edge, int base, int rise, Cell from, Cell to, RegionAgent<Toy> agent) {
    ToyWorld.Blocks ledge =
        (x, y, z) ->
            y <= (x >= edge ? base + rise : base) ? ToyWorld.Material.STONE : ToyWorld.Material.AIR;
    return estimate(ToyWorld.spec(), new ToyWorld.Source(ledge), agent, from, to);
  }

  /** A fall too deep for one agent is still in the graph: whether to take it is the agent's. */
  @ParameterizedTest
  @ValueSource(ints = {8, 16})
  void aDeepFallIsTheAgentsToTake(int edge) {
    // From y 16, a section's floor, to y 11 in the section below.
    Cell top = new Cell(edge + 5, 16, 4);
    Cell bottom = new Cell(edge - 5, 11, 4);
    double distance = top.distance(bottom);

    assertEquals(2.8 * distance, across(edge, 10, 5, top, bottom, new NoDigging(3)), 1e-9);
    assertTrue(across(edge, 10, 5, top, bottom, new NoDigging(5)) < 0.2 * distance * 1.5);
  }

  /** A fall is followed down through as many sections as it crosses, here two floors. */
  @ParameterizedTest
  @ValueSource(ints = {8, 16})
  void aFallIsFollowedDownThroughTheSectionsBelow(int edge) {
    // A cliff of stone to y 40 beside a pool at y 10 and 11: thirty blocks down into water.
    ToyWorld.Blocks cliff =
        (x, y, z) -> {
          if (x >= edge) {
            return y <= 40 ? ToyWorld.Material.STONE : ToyWorld.Material.AIR;
          }
          if (y <= 9) {
            return ToyWorld.Material.STONE;
          }
          return y <= 11 ? ToyWorld.Material.WATER : ToyWorld.Material.AIR;
        };
    Cell top = new Cell(edge + 5, 41, 4);
    Cell pool = new Cell(edge - 5, 11, 4);

    double down = estimate(ToyWorld.spec(), new ToyWorld.Source(cliff), new NoDigging(), top, pool);

    assertTrue(down < 0.3 * top.distance(pool) * 1.5, "into the water: " + down);
  }

  /**
   * Steps and falls join regions the same way across a seam between sections as inside one: side by
   * side at {@code edge 16}, and above and below where the ground at {@code base 14} puts a step or
   * a ledge across a section's floor.
   */
  @ParameterizedTest
  @CsvSource({"8, 10", "16, 10", "8, 14", "16, 14"})
  void stepsAndFallsJoinTheSameAcrossASeam(int edge, int base) {
    Cell low = new Cell(edge - 5, base + 1, 4);
    Cell oneUp = new Cell(edge + 5, base + 2, 4);
    Cell threeUp = new Cell(edge + 5, base + 4, 4);
    double distance = low.distance(oneUp);
    RegionAgent<Toy> walker = new NoDigging();

    // A step is walked both ways.
    assertTrue(across(edge, base, 1, low, oneUp, walker) < 0.2 * distance * 1.5);
    assertTrue(across(edge, base, 1, oneUp, low, walker) < 0.2 * distance * 1.5);
    // A ledge is fallen from, and cannot be climbed.
    assertTrue(across(edge, base, 3, threeUp, low, walker) < 0.2 * low.distance(threeUp) * 1.5);
    assertEquals(2.8 * low.distance(threeUp), across(edge, base, 3, low, threeUp, walker), 1e-9);
  }
}
