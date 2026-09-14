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

class Tier1EstimatorTest {

  /** The cheapest a block of travel can cost this agent — flight, as production would pass. */
  private static final double CHEAPEST = 0.08;

  private static final TestDomain OVERWORLD = new TestDomain("overworld");
  private static final TestDomain NETHER = new TestDomain("nether");

  private static final Cell ORIGIN = new Cell(0, 64, 0);

  private static DomainRegion<TestDomain> target(int distance, TestDomain domain) {
    return new CellRegion<>(new Cell(distance, 64, 0), domain);
  }

  private static Tier1Estimator estimator(double pessimism) {
    return new Tier1Estimator(Heuristics.euclidean(CHEAPEST), pessimism);
  }

  private static double estimate(Tier1Estimator estimator, DomainRegion<TestDomain> target) {
    return estimator.estimate(ORIGIN, target, TraversalState.DEFAULT);
  }

  @Test
  void withNothingSolvedYetALegIsPricedAtTheAdmissibleBound() {
    Tier1Estimator estimator = estimator(1.0);
    assertEquals(1000 * CHEAPEST, estimate(estimator, target(1000, OVERWORLD)), 1e-9);
  }

  @Test
  void thePessimismMarginScalesTheEstimate() {
    Tier1Estimator estimator = estimator(1.5);
    assertEquals(1000 * CHEAPEST * 1.5, estimate(estimator, target(1000, OVERWORLD)), 1e-9);
  }

  /**
   * The whole point: a leg that came back three times dearer than its bound re-prices every leg
   * still unsolved in that world, so the route already committed to has to be genuinely beaten
   * rather than beaten by a promise nothing can keep.
   */
  @Test
  void aSolvedLegRepricesTheRestOfItsWorld() {
    Tier1Estimator estimator = estimator(1.0);
    DomainRegion<TestDomain> solved = target(1000, OVERWORLD);
    double bound = estimate(estimator, solved);

    estimator.observe(ORIGIN, solved, TraversalState.DEFAULT, bound * 3);

    assertEquals(500 * CHEAPEST * 3, estimate(estimator, target(500, OVERWORLD)), 1e-9);
  }

  @Test
  void severalLegsInOneWorldAverageByHowMuchTravellingEachRepresents() {
    Tier1Estimator estimator = estimator(1.0);
    // A thousand blocks at 4x, then ten blocks at 1x: the short leg barely moves the factor.
    estimator.observe(ORIGIN, target(1000, OVERWORLD), TraversalState.DEFAULT, 1000 * CHEAPEST * 4);
    estimator.observe(ORIGIN, target(10, OVERWORLD), TraversalState.DEFAULT, 10 * CHEAPEST);

    double factor = estimate(estimator, target(100, OVERWORLD)) / (100 * CHEAPEST);
    assertEquals((1000 * 4 + 10) / 1010.0, factor, 1e-9);
  }

  /**
   * A world nothing has solved in is priced by the least pessimistic sample there is, so an
   * unvisited world is never ruled out on a guess. It corrects itself the moment a leg is solved
   * there.
   */
  @Test
  void anUnsampledWorldBorrowsTheLeastPessimisticSample() {
    Tier1Estimator estimator = estimator(1.0);
    estimator.observe(ORIGIN, target(1000, OVERWORLD), TraversalState.DEFAULT, 1000 * CHEAPEST * 2);

    assertEquals(1000 * CHEAPEST * 2, estimate(estimator, target(1000, NETHER)), 1e-9);

    // The nether turns out far worse; the overworld's factor is still the lower of the two, so an
    // unsampled third world would take that one rather than the nether's.
    estimator.observe(ORIGIN, target(1000, NETHER), TraversalState.DEFAULT, 1000 * CHEAPEST * 7);
    assertEquals(1000 * CHEAPEST * 7, estimate(estimator, target(1000, NETHER)), 1e-9);
    assertEquals(
        1000 * CHEAPEST * 2, estimate(estimator, target(1000, new TestDomain("end"))), 1e-9);
  }

  @Test
  void aLegCheaperThanItsBoundDoesNotMakeTheEstimateMoreOptimistic() {
    Tier1Estimator estimator = estimator(1.0);
    // Not physically possible (the bound is a lower bound), but the floor must hold regardless.
    estimator.observe(
        ORIGIN, target(1000, OVERWORLD), TraversalState.DEFAULT, 1000 * CHEAPEST * 0.25);

    assertEquals(1000 * CHEAPEST, estimate(estimator, target(1000, OVERWORLD)), 1e-9);
  }

  @Test
  void onePathologicalLegCannotPriceItsWorldOutOfContention() {
    Tier1Estimator estimator = estimator(1.0);
    estimator.observe(
        ORIGIN, target(1000, OVERWORLD), TraversalState.DEFAULT, 1000 * CHEAPEST * 500);

    double factor = estimate(estimator, target(1000, OVERWORLD)) / (1000 * CHEAPEST);
    assertTrue(factor <= 20.0, "capped, got " + factor);
    assertTrue(factor > 1.0);
  }

  @Test
  void aLegTooShortToLearnFromIsIgnored() {
    Tier1Estimator estimator = estimator(1.0);
    estimator.observe(ORIGIN, target(3, OVERWORLD), TraversalState.DEFAULT, 100.0);

    assertEquals(1000 * CHEAPEST, estimate(estimator, target(1000, OVERWORLD)), 1e-9);
  }

  /** An uninformed heuristic has no bound to measure against, and must stay uninformed. */
  @Test
  void theZeroHeuristicLearnsNothingAndStaysAtZero() {
    Tier1Estimator estimator = new Tier1Estimator(Heuristics.zero(), 1.5);
    estimator.observe(ORIGIN, target(1000, OVERWORLD), TraversalState.DEFAULT, 900.0);

    assertEquals(0.0, estimate(estimator, target(1000, OVERWORLD)), 1e-9);
  }
}
