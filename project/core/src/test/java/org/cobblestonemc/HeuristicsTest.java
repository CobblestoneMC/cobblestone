/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cobblestonemc.api.TraversalState;
import org.junit.jupiter.api.Test;

class HeuristicsTest {

  private static final double CHEAPEST = 0.08; // the fly cost, as production uses
  private static final double WALK = 0.20;
  private static final double MINE = 0.56; // stone, stone pickaxe
  private static final int WIDTH = 5;

  private static final TestDomain WORLD = new TestDomain("overworld");
  private static final DomainRegion<TestDomain> GOAL =
      new CellRegion<>(new Cell(2000, 64, 0), WORLD);

  private static SolveHeuristic solve() {
    return Heuristics.runningAverage(CHEAPEST).newSolve(WIDTH);
  }

  private static double estimate(SolveHeuristic heuristic, Cell cell, double trailAverage) {
    return heuristic.estimate(cell, GOAL, TraversalState.DEFAULT, trailAverage);
  }

  /** Walks {@code steps} one-block steps of the given cost, returning the trail average. */
  private static double walkTrail(SolveHeuristic heuristic, double stepCost, int steps) {
    double average = heuristic.seed();
    for (int i = 0; i < steps; i++) {
      average = heuristic.advance(average, stepCost, 1.0);
    }
    return average;
  }

  @Test
  void seedIsTheGlobalLowerBound() {
    assertEquals(CHEAPEST, solve().seed());
  }

  @Test
  void trailAverageConvergesOnTheCostBeingPaid() {
    SolveHeuristic heuristic = solve();
    // Convergence is asymptotic — the seed's weight decays but never quite reaches zero.
    double average = walkTrail(heuristic, WALK, 40);
    assertEquals(WALK, average, 1e-3);
  }

  @Test
  void advanceMovesTowardTheSampleWithoutOvershooting() {
    SolveHeuristic heuristic = solve();
    double average = heuristic.advance(WALK, MINE, 1.0);
    assertTrue(average > WALK, "should rise toward the pricier sample");
    assertTrue(average < MINE, "one step should not jump the whole way");
  }

  @Test
  void aLongerStepMovesTheAverageFurtherThanAShortOne() {
    SolveHeuristic heuristic = solve();
    double oneBlock = heuristic.advance(WALK, MINE, 1.0);
    double tenBlocks = heuristic.advance(WALK, MINE * 10, 10.0);
    assertTrue(tenBlocks > oneBlock, "a ten-block step should count for ten samples, not one");
    assertTrue(tenBlocks < MINE);
  }

  @Test
  void aStepCoveringNoGroundTeachesNothing() {
    SolveHeuristic heuristic = solve();
    assertEquals(WALK, heuristic.advance(WALK, 12.0, 0.0));
  }

  @Test
  void trailsThroughDifferentTerrainDivergeRatherThanSharingOneAverage() {
    SolveHeuristic heuristic = solve();
    double onGrass = walkTrail(heuristic, WALK, 20);
    double throughRock = walkTrail(heuristic, MINE, 20);
    assertTrue(
        throughRock > onGrass * 2,
        "two trails in different terrain must carry their own averages, not one shared one");
  }

  /**
   * What this heuristic exists for. Priced at open-ground cost, a cell a few blocks into a hillside
   * has an {@code f} within a second or two of the cell on the grass beside it across a
   * two-thousand-block journey, and A* bores a cone into every hill it passes. Pricing each cell by
   * its own trail has to separate the two decisively.
   */
  @Test
  void diggingIntoAHillIsPricedWellAboveWalkingAroundIt() {
    SolveHeuristic heuristic = solve();

    // Three blocks into rock, having walked the approach.
    double approach = walkTrail(heuristic, WALK, 20);
    double diggingAverage = approach;
    double diggingCost = 0.0;
    for (int i = 0; i < 3; i++) {
      diggingAverage = heuristic.advance(diggingAverage, MINE, 1.0);
      diggingCost += MINE;
    }
    Cell dug = new Cell(3, 64, 0);
    double diggingF = diggingCost + estimate(heuristic, dug, diggingAverage);

    // Three blocks of detour along the surface instead.
    double detourCost = 3 * WALK;
    Cell detoured = new Cell(0, 64, 3);
    double detourF = detourCost + estimate(heuristic, detoured, approach);

    assertTrue(
        diggingF > detourF * 1.5,
        "digging (f=%.1f) must be decisively worse than detouring (f=%.1f)"
            .formatted(diggingF, detourF));
  }

  @Test
  void statelessStrategiesIgnoreTheTrailAverage() {
    SolveHeuristic heuristic = Heuristics.euclidean(CHEAPEST).newSolve(WIDTH);
    Cell cell = new Cell(0, 64, 0);
    double withSeed = estimate(heuristic, cell, heuristic.seed());
    double withNonsense = estimate(heuristic, cell, 99.0);
    assertEquals(withSeed, withNonsense);
    assertEquals(cell.distance(new Cell(2000, 64, 0)) * CHEAPEST, withSeed, 1e-9);
  }
}
