/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.junit.jupiter.api.Test;

class RegionSearchTest {

  private record TestBlock(boolean passable, boolean solidTop, boolean water, double breakTime)
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
    public boolean isWater() {
      return water;
    }

    @Override
    public double breakTimeSeconds() {
      return breakTime;
    }
  }

  private static final MinecraftBlock AIR = new TestBlock(true, false, false, 0.0);
  private static final MinecraftBlock STONE = new TestBlock(false, true, false, 1.5);
  private static final MinecraftBlock BEDROCK =
      new TestBlock(false, true, false, Double.POSITIVE_INFINITY);
  private static final MinecraftBlock WATER = new TestBlock(false, false, true, 0.0);

  /** Ground level: the top of the stone, so a body stands at {@code GROUND + 1}. */
  private static final int GROUND = 10;

  private static final int FEET = GROUND + 1;

  /** A world described by a function of world coordinates, read a column at a time. */
  private interface Terrain {
    MinecraftBlock at(int x, int y, int z);
  }

  private static final class World implements RegionGraph.ChunkColumns {
    private final Terrain terrain;
    int read;

    World(Terrain terrain) {
      this.terrain = terrain;
    }

    @Override
    public FutureOr<MinecraftChunk> column(int chunkX, int chunkZ) {
      read++;
      MinecraftChunk chunk = (x, y, z) -> terrain.at(chunkX * 16 + x, y, chunkZ * 16 + z);
      return FutureOr.of(chunk);
    }

    @Override
    public int minY() {
      return 0;
    }

    @Override
    public int maxY() {
      return 47;
    }
  }

  private static MinecraftBlock flat(int x, int y, int z) {
    return y <= GROUND ? STONE : AIR;
  }

  private static RegionSearch search(
      Terrain terrain, Set<Capability> capabilities, Cell goal, Cell from) {
    return new RegionSearch(
        new RegionGraph(new World(terrain)), RegionCost.forCapabilities(capabilities), goal, from);
  }

  private static Set<Capability> walker() {
    return RegionCost.survival();
  }

  private static Set<Capability> walkerWithoutPickaxe() {
    Set<Capability> capabilities = EnumSet.copyOf(RegionCost.survival());
    capabilities.remove(Capability.MINE);
    return capabilities;
  }

  @Test
  void flatGroundIsPricedAtTheWalkingRate() {
    Cell goal = new Cell(0, FEET, 0);
    Cell from = new Cell(200, FEET, 37);
    RegionSearch search = search(RegionSearchTest::flat, walker(), goal, from);

    double estimate = search.costToGoal(from);
    double straight = from.distance(goal) * Capability.WALK.baseCostPerBlock();

    assertNull(search.takePending());
    // Section-sized regions priced in straight lines, crossing at the points the route does: a
    // diagonal route across a plain must not pay for a staircase of section faces.
    assertTrue(
        estimate >= straight * 0.97 && estimate <= straight * 1.08,
        "flat ground should cost about the straight walk: " + estimate + " vs " + straight);
  }

  @Test
  void anUnbreakableWallIsPricedAsTheWayAroundIt() {
    // A bedrock wall along x = 64 from z = -200 to z = 200, top to bottom, so the only way past is
    // around an end: not over it, and not dug under.
    Terrain walled = (x, y, z) -> x == 64 && z > -200 && z < 200 ? BEDROCK : flat(x, y, z);
    Cell goal = new Cell(100, FEET, 0);
    Cell from = new Cell(30, FEET, 0);
    RegionSearch search = search(walled, walker(), goal, from);

    double estimate = search.costToGoal(from);
    double around = (Math.hypot(34, 200) + Math.hypot(36, 200)) * 0.2;

    // Slice coverage could not see this: every slice of every section here holds open ground.
    assertTrue(
        estimate >= around * 0.9,
        "the estimate should know the wall is there: " + estimate + " vs around " + around);
  }

  @Test
  void aBreakableWallIsDugThroughByAMinerAndWalkedAroundByAWalker() {
    // Stone this time: a miner digs one block, a walker without a pickaxe has to go around.
    Terrain walled =
        (x, y, z) -> x == 64 && z > -200 && z < 200 && y > GROUND ? STONE : flat(x, y, z);
    Cell goal = new Cell(100, FEET, 0);
    Cell from = new Cell(30, FEET, 0);

    double miner = search(walled, walker(), goal, from).costToGoal(from);
    double walker = search(walled, walkerWithoutPickaxe(), goal, from).costToGoal(from);

    assertTrue(miner < 70 * 0.2 + 15, "a miner digs through: " + miner);
    assertTrue(walker > miner, "a walker who cannot dig pays more: " + walker + " vs " + miner);
  }

  @Test
  void aFallIsOneWay() {
    // A cliff three blocks high at x = 40: ground at GROUND + 3 for x < 40, GROUND beyond.
    Terrain cliff = (x, y, z) -> y <= (x < 40 ? GROUND + 3 : GROUND) ? STONE : AIR;
    Cell top = new Cell(20, FEET + 3, 0);
    Cell bottom = new Cell(60, FEET, 0);

    double down = search(cliff, walkerWithoutPickaxe(), bottom, top).costToGoal(top);
    double up = search(cliff, walkerWithoutPickaxe(), top, bottom).costToGoal(bottom);

    assertTrue(down < 40 * 0.2 * 1.15, "stepping off the edge is cheap: " + down);
    // With no pickaxe there is no way back up at all; the graph prices the face as rock at the
    // fallback rate, so at least a couple of blocks of it.
    assertTrue(
        up > down + 1.5 * RegionCost.FALLBACK_COST_PER_BLOCK,
        "and climbing back is not: " + up + " vs " + down);
  }

  @Test
  void aBoatPricesARiverBelowWalkingItsBank() {
    // A river two blocks deep along z in [0, 8), flowing the length of the route.
    Terrain river =
        (x, y, z) -> {
          if (z >= 0 && z < 8 && y > GROUND - 2 && y <= GROUND) {
            return WATER;
          }
          return flat(x, y, z);
        };
    Cell goal = new Cell(300, GROUND, 4);
    Cell from = new Cell(0, GROUND, 4);
    Set<Capability> boater = EnumSet.copyOf(RegionCost.survival());
    boater.add(Capability.BOAT);

    double boat = search(river, boater, goal, from).costToGoal(from);
    double swim = search(river, walker(), goal, from).costToGoal(from);

    assertTrue(boat < swim, "a boat should make the river cheaper: " + boat + " vs " + swim);
    assertTrue(boat < 300 * 0.2, "and cheaper than walking the bank: " + boat);
  }

  @Test
  void answersAreFinalAndRepeatQueriesAreFree() {
    Cell goal = new Cell(0, FEET, 0);
    Cell from = new Cell(300, FEET, 0);
    RegionSearch search = search(RegionSearchTest::flat, walker(), goal, from);

    double first = search.costToGoal(new Cell(150, FEET, 5));
    search.costToGoal(from);
    long expansions = search.expansions();
    double again = search.costToGoal(new Cell(150, FEET, 5));

    assertEquals(first, again, "running the search further must not change an answer");
    assertEquals(expansions, search.expansions(), "an answered cell costs no more search");
  }

  @Test
  void theSearchStaysNearTheRouteItIsAskedAbout() {
    Cell goal = new Cell(0, FEET, 0);
    Cell from = new Cell(400, FEET, 0);
    World world = new World(RegionSearchTest::flat);
    RegionSearch search =
        new RegionSearch(new RegionGraph(world), RegionCost.forCapabilities(walker()), goal, from);

    search.costToGoal(from);

    // Focused on the origin: a corridor between the two ends, not a disc of radius 400 around the
    // goal. A disc would read on the order of 2,000 columns.
    assertTrue(world.read < 400, "the search should not flood the world: " + world.read);
  }

  @Test
  void anAreaDestinationIsReachedByItsNearestEntranceNotItsNearestEdge() {
    // A walled town, x 100..160 and z -30..30, in bedrock: its only gate is on the east side, away
    // from a traveller coming from the west. The nearest cell of the town is just inside the west
    // wall, but the town is reached the moment the gate is passed.
    Terrain town =
        (x, y, z) -> {
          boolean westOrEast = (x == 100 || x == 160) && z >= -30 && z <= 30;
          boolean northOrSouth = (z == -30 || z == 30) && x >= 100 && x <= 160;
          boolean gate = x == 160 && z >= -2 && z <= 2;
          return (westOrEast || northOrSouth) && !gate ? BEDROCK : flat(x, y, z);
        };
    Predicate<Cell> inside =
        cell -> cell.x() > 100 && cell.x() < 160 && cell.z() > -30 && cell.z() < 30;
    Cell from = new Cell(60, FEET, 0);
    Cell nearest = new Cell(101, FEET, 0);
    RegionCost cost = RegionCost.forCapabilities(walker());

    double toPoint =
        new RegionSearch(new RegionGraph(new World(town)), cost, nearest, from).costToGoal(from);
    double toArea =
        new RegionSearch(new RegionGraph(new World(town)), cost, nearest, from, inside)
            .costToGoal(from);

    double around = (Math.hypot(40, 31) + 60 + Math.hypot(1, 31)) * 0.2;
    assertTrue(
        toArea < toPoint - 8,
        "arriving through the gate should not pay to cross the town: " + toArea + " vs " + toPoint);
    assertTrue(
        toArea >= around * 0.8, "the walls are still walls: " + toArea + " vs around " + around);
  }
}
