/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.function.ToDoubleFunction;
import org.cobblestonemc.api.TraversalState;

/**
 * Factory for the built-in {@link HeuristicStrategy} implementations: the admissible {@link #zero}
 * and {@link #euclidean} (used for exact-optimal results and unit tests), and the adaptive {@link
 * #runningAverage} used in production for speed.
 */
public final class Heuristics {

  private Heuristics() {}

  /**
   * A trivially-admissible heuristic that always returns {@code 0}, turning Tier-2 A* into
   * uniform-cost (Dijkstra) search — always optimal for the explored terrain, but uninformed.
   *
   * @return the zero heuristic
   */
  public static HeuristicStrategy zero() {
    return (from, target, state) -> 0.0;
  }

  /**
   * Euclidean distance to the region's nearest boundary cell, times a per-domain cost per block.
   *
   * <p>Admissible exactly when {@code costPerBlock} is a true lower bound on the cost of moving one
   * block by any available means. Production tuning deliberately passes a <i>typical</i> cost
   * instead (see {@link #runningAverage}), which trades admissibility for an estimate that is not
   * routinely beaten several times over by real terrain.
   *
   * @param costPerBlock per-block traversal cost, in seconds, for the domain being crossed
   * @return the euclidean heuristic
   */
  public static HeuristicStrategy euclidean(ToDoubleFunction<Domain> costPerBlock) {
    return (from, target, state) ->
        from.distance(target.nearestBoundaryCell(from)) * cost(costPerBlock, target);
  }

  /**
   * Euclidean distance times one cost per block, the same in every domain.
   *
   * @param costPerBlock per-block traversal cost, in seconds
   * @return the euclidean heuristic
   */
  public static HeuristicStrategy euclidean(double costPerBlock) {
    requireNonNegative(costPerBlock);
    return euclidean(domain -> costPerBlock);
  }

  /**
   * A production heuristic that scales the remaining distance by the average per-block cost of the
   * trail leading to the cell being estimated — so {@code h} tracks the terrain that cell is
   * actually in, and A* explores far fewer cells. The price is admissibility, so paths may be
   * slightly sub-optimal (weighted-A*-style). Tier-1 still uses {@link
   * HeuristicStrategy#estimate(Cell, DomainRegion, TraversalState)} (distance × {@code
   * averageCostPerBlock}).
   *
   * <p><b>Locality is the point.</b> Consider a cell three blocks inside a hillside, two thousand
   * blocks from the goal. Pricing its remaining journey at the cost of open ground makes its {@code
   * f} within a second or two of the cell on the grass beside it, so A* bores a cone into every
   * hill it passes. Averaging only over the trail behind <i>that</i> cell prices its remaining
   * journey as rock, and the digging branch drops out of contention immediately.
   *
   * <p><b>The cost is per domain.</b> A block of nether is not a block of overworld: the terrain is
   * more broken, the routes wind more, and the same straight-line distance costs more to walk. A
   * single number across a route that crosses a portal prices one side of it wrongly, so the lookup
   * is by {@link Domain}.
   *
   * @param averageCostPerBlock the typical per-block cost of travel in a domain, in seconds; seeds
   *     a trail and prices Tier-1's unsolved legs
   * @return the running-average heuristic
   */
  public static HeuristicStrategy runningAverage(ToDoubleFunction<Domain> averageCostPerBlock) {
    return new HeuristicStrategy() {
      @Override
      public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
        return from.distance(target.nearestBoundaryCell(from)) * cost(averageCostPerBlock, target);
      }

      @Override
      public SolveHeuristic newSolve(int windowWidth, DomainRegion<?> target) {
        return new RunningAverageSolve(cost(averageCostPerBlock, target), windowWidth);
      }
    };
  }

  /**
   * The running-average heuristic with one cost per block, the same in every domain.
   *
   * @param averageCostPerBlock the typical per-block cost of travel, in seconds
   * @return the running-average heuristic
   */
  public static HeuristicStrategy runningAverage(double averageCostPerBlock) {
    requireNonNegative(averageCostPerBlock);
    return runningAverage(domain -> averageCostPerBlock);
  }

  private static double cost(ToDoubleFunction<Domain> costPerBlock, DomainRegion<?> target) {
    return requireNonNegative(costPerBlock.applyAsDouble(target.domain()));
  }

  private static double requireNonNegative(double costPerBlock) {
    if (!(costPerBlock >= 0)) { // also rejects NaN
      throw new IllegalArgumentException("costPerBlock must be >= 0: " + costPerBlock);
    }
    return costPerBlock;
  }

  /**
   * Scales distance by the trail average each cell carries, folded forward with an exponential
   * moving average of width {@code windowWidth}.
   *
   * <p>An EMA rather than an exact last-{@code n} window: it is a single {@code double} per cell
   * instead of an array, and it decays with distance from a feature rather than dropping it
   * abruptly, so the estimate eases back toward open-ground cost as a trail leaves a wall behind
   * instead of stepping down when the wall falls off the end of a buffer.
   */
  private static final class RunningAverageSolve implements SolveHeuristic {

    private final double averageCostPerBlock;

    /** The weight one block of travel keeps of the previous average; {@code 1 - 1/width}. */
    private final double retention;

    RunningAverageSolve(double averageCostPerBlock, int windowWidth) {
      this.averageCostPerBlock = averageCostPerBlock;
      this.retention = 1.0 - 1.0 / Math.max(1, windowWidth);
    }

    @Override
    public double seed() {
      return averageCostPerBlock;
    }

    @Override
    public double advance(double trailAverage, double stepCost, double blocks) {
      if (blocks <= 0.0) {
        return trailAverage; // a transition in place covers no ground and teaches nothing
      }
      // Weight the sample by the step's length, so one ten-block fall counts as much as ten
      // one-block steps would rather than as a single sample.
      double weight = 1.0 - Math.pow(retention, blocks);
      return trailAverage + (stepCost / blocks - trailAverage) * weight;
    }

    @Override
    public double estimate(Cell from, double distance, TraversalState state, double trailAverage) {
      return distance * trailAverage;
    }
  }
}
