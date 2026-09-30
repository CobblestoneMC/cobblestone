/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.cobblestonemc.Cell;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.junit.jupiter.api.Test;

class CoarseSearchTest {

  private record TestBlock(boolean passable, boolean solidTop, double breakTime)
      implements MinecraftBlock {
    @Override
    public boolean isPassable() {
      return passable;
    }

    @Override
    public boolean isSolidTop() {
      return solidTop;
    }

    @Override
    public double breakTimeSeconds() {
      return breakTime;
    }
  }

  private static final MinecraftBlock AIR = new TestBlock(true, false, 0.0);
  private static final MinecraftBlock STONE = new TestBlock(false, true, 1.5);

  /** Flat walkable ground everywhere, profiled on demand. */
  private static final class FlatWorld implements CoarseSearch.SectionProfiles {
    private final Map<Long, SectionProfile> cache = new HashMap<>();
    private final SectionProfiler profiler = new SectionProfiler();
    int profiled;

    @Override
    public org.cobblestonemc.FutureOr<SectionProfile> at(int sx, int sy, int sz) {
      if (sy != 0) {
        // One layer of sections, so the search cannot wander vertically.
        return org.cobblestonemc.FutureOr.of(null);
      }
      return org.cobblestonemc.FutureOr.of(profile(sx, sy, sz));
    }

    private SectionProfile profile(int sx, int sy, int sz) {
      return cache.computeIfAbsent(
          CoarseSearch.key(sx, sy, sz),
          key -> {
            profiled++;
            MinecraftChunk chunk = (x, y, z) -> Math.floorMod(y, 16) <= 3 ? STONE : AIR;
            return profiler.profile(chunk, 0);
          });
    }
  }

  @Test
  void keysRoundTripAcrossTheWholeWorldHeight() {
    // Section Y runs -4..19 in a modern overworld; five bits would have wrapped the top of it.
    for (int sy : new int[] {-64, -4, 0, 19, 63}) {
      for (int sx : new int[] {-2_000_000, -1, 0, 1, 2_000_000}) {
        long key = CoarseSearch.key(sx, sy, -sx);
        assertEquals(sx, CoarseSearch.unpackX(key), "x " + sx);
        assertEquals(-sx, CoarseSearch.unpackZ(key), "z " + (-sx));
        assertEquals(sy, CoarseSearch.unpackY(key), "y " + sy);
      }
    }
  }

  @Test
  void costRisesWithDistanceFromTheGoal() {
    FlatWorld world = new FlatWorld();
    CoarseSearch search =
        new CoarseSearch(world, CoarseCost.forMediums(CoarseCost.survival()), new Cell(8, 8, 8));

    double near = search.costToGoal(new Cell(40, 8, 8), 10_000);
    double far = search.costToGoal(new Cell(200, 8, 8), 10_000);

    assertTrue(near > 0, "a different section should cost something");
    assertTrue(far > near, "further away should cost more: " + far + " vs " + near);
  }

  @Test
  void theSearchOnlyProfilesWhatItNeeds() {
    FlatWorld world = new FlatWorld();
    CoarseSearch search =
        new CoarseSearch(world, CoarseCost.forMediums(CoarseCost.survival()), new Cell(8, 8, 8));

    search.costToGoal(new Cell(40, 8, 8), 10_000);
    int afterNear = world.profiled;
    search.costToGoal(new Cell(2000, 8, 8), 10_000);

    // Lazily expanded: reaching further costs more profiling, and nothing already done is redone.
    assertTrue(world.profiled > afterNear, "reaching further should profile more sections");
    assertTrue(afterNear < 200, "a nearby query should not profile the world: " + afterNear);
  }

  @Test
  void repeatedQueriesReuseSettledWork() {
    FlatWorld world = new FlatWorld();
    CoarseSearch search =
        new CoarseSearch(world, CoarseCost.forMediums(CoarseCost.survival()), new Cell(8, 8, 8));

    double first = search.costToGoal(new Cell(200, 8, 8), 10_000);
    long expansionsAfterFirst = search.expansions();
    double second = search.costToGoal(new Cell(200, 8, 8), 10_000);

    assertEquals(first, second);
    assertEquals(expansionsAfterFirst, search.expansions(), "a settled answer must be free");
  }

  @Test
  void anUnreachableQueryFallsBackOptimisticallyRatherThanToInfinity() {
    FlatWorld world = new FlatWorld();
    CoarseSearch search =
        new CoarseSearch(world, CoarseCost.forMediums(CoarseCost.survival()), new Cell(8, 8, 8));

    // Far above the one profiled section layer: the coarse graph cannot reach it at all.
    double estimate = search.costToGoal(new Cell(8, 5000, 8), 10_000);

    assertTrue(Double.isFinite(estimate), "never infinity, or a bad profile becomes a wall");
    assertTrue(estimate > 0);
  }

  @Test
  void aBudgetedQueryStillAnswers() {
    FlatWorld world = new FlatWorld();
    CoarseSearch search =
        new CoarseSearch(world, CoarseCost.forMediums(CoarseCost.survival()), new Cell(8, 8, 8));

    double estimate = search.costToGoal(new Cell(10_000, 8, 8), 5);

    assertTrue(Double.isFinite(estimate));
    assertTrue(search.expansions() <= 5, "the budget must be respected");
  }
}
