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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegionColumnBuilderTest {

  /** Walks ground and swims water, and cannot dig: only portals join anything for it. */
  private static final class NoDigging implements RegionAgent {
    private final ToyWorld.Walker walker = new ToyWorld.Walker();

    @Override
    public boolean usable(int clazz) {
      return clazz == ToyWorld.Terrain.GROUND || clazz == ToyWorld.Terrain.POOL;
    }

    @Override
    public double traverse(
        int clazz, double fx, double fy, double fz, double tx, double ty, double tz) {
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
    RegionSpec<ToyWorld.Column> spec = ToyWorld.spec();
    ToyWorld.Column column = new ToyWorld.Source(checkerboard, 16).column(0, 0).value();
    RegionColumn built = new RegionColumnBuilder<>(spec).build(0, 0, column);

    assertNotNull(built.wideCells[1], "more than 255 regions: a short per cell");
    assertTrue(built.regions() > 2048);
    for (int ly = 16; ly < 32; ly++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          assertNotEquals(RegionColumn.NONE, built.regionAt(x, ly, z), "no cell left a hole");
        }
      }
    }
    // Two stone cells a diagonal apart are separate regions.
    assertNotEquals(built.regionAt(0, 16, 0), built.regionAt(1, 17, 0));
  }

  /**
   * Ground stepping up {@code rise} blocks at {@code x = edge}: a step at 1, a ledge at 3.
   *
   * @return the estimate from {@code from} to {@code to} for an agent that cannot dig
   */
  private static double across(int edge, int rise, Cell from, Cell to) {
    ToyWorld.Blocks ledge =
        (x, y, z) ->
            y <= (x >= edge ? 10 + rise : 10) ? ToyWorld.Material.STONE : ToyWorld.Material.AIR;
    RegionStore<ToyWorld.Column> store = RegionStore.inMemory(ToyWorld.spec());
    RegionGraph<ToyWorld.Column> graph = new RegionGraph<>(store, new ToyWorld.Source(ledge, 16));
    return new RegionSearch(graph, new NoDigging(), from, to::equals, cell -> to).costToGoal(from);
  }

  /** Steps and falls join regions the same way across a seam between columns as inside one. */
  @ParameterizedTest
  @ValueSource(ints = {8, 16})
  void stepsAndFallsJoinTheSameAcrossASeam(int edge) {
    Cell low = new Cell(edge - 5, 11, 4);
    Cell oneUp = new Cell(edge + 5, 12, 4);
    Cell threeUp = new Cell(edge + 5, 14, 4);
    double distance = low.distance(oneUp);

    // A step is walked both ways.
    assertTrue(across(edge, 1, low, oneUp) < 0.2 * distance * 1.5);
    assertTrue(across(edge, 1, oneUp, low) < 0.2 * distance * 1.5);
    // A ledge is fallen from, and cannot be climbed.
    assertTrue(across(edge, 3, threeUp, low) < 0.2 * low.distance(threeUp) * 1.5);
    assertEquals(2.8 * low.distance(threeUp), across(edge, 3, low, threeUp), 1e-9);
  }
}
