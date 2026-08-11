/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

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
   * An admissible, consistent heuristic: euclidean distance to the region's nearest boundary cell
   * times a globally-cheapest per-block cost.
   *
   * <p>{@code cheapestCostPerBlock} must be a true lower bound on the cost of moving one block by
   * any available means (e.g. the fastest mode's per-block cost); using a global lower bound keeps
   * the estimate admissible for every agent without needing per-agent knowledge here.
   *
   * @param cheapestCostPerBlock a lower bound on per-block traversal cost, in seconds
   * @return the euclidean heuristic
   */
  public static HeuristicStrategy euclidean(double cheapestCostPerBlock) {
    if (cheapestCostPerBlock < 0) {
      throw new IllegalArgumentException(
          "cheapestCostPerBlock must be >= 0: " + cheapestCostPerBlock);
    }
    return (from, target, state) ->
        from.distance(target.nearestBoundaryCell(from)) * cheapestCostPerBlock;
  }

  /**
   * A production heuristic that scales the remaining distance by a sliding-window average of the
   * real per-block cost seen so far in the current solve (falling back to {@code
   * cheapestCostPerBlock} before any samples). This makes {@code h} track the terrain/mode actually
   * being traversed, so A* explores far fewer cells — at the price of admissibility, so paths may
   * be slightly sub-optimal (weighted-A*-style). Tier-1 still uses the admissible {@link #estimate}
   * (distance × {@code cheapestCostPerBlock}).
   *
   * @param cheapestCostPerBlock a lower bound on per-block cost, used before samples and by Tier-1
   * @return the running-average heuristic
   */
  public static HeuristicStrategy runningAverage(double cheapestCostPerBlock) {
    if (cheapestCostPerBlock < 0) {
      throw new IllegalArgumentException(
          "cheapestCostPerBlock must be >= 0: " + cheapestCostPerBlock);
    }
    return new HeuristicStrategy() {
      @Override
      public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
        return from.distance(target.nearestBoundaryCell(from)) * cheapestCostPerBlock;
      }

      @Override
      public SolveHeuristic newSolve(int windowWidth) {
        return new RunningAverageSolve(cheapestCostPerBlock, windowWidth);
      }
    };
  }

  /** A per-solve heuristic scaling distance by a rolling average of recent real per-block costs. */
  private static final class RunningAverageSolve implements SolveHeuristic {

    private final double cheapestCostPerBlock;
    private final double[] window;
    private int count;
    private int next;
    private double sum;

    RunningAverageSolve(double cheapestCostPerBlock, int windowWidth) {
      this.cheapestCostPerBlock = cheapestCostPerBlock;
      this.window = new double[Math.max(1, windowWidth)];
    }

    @Override
    public double estimate(Cell from, DomainRegion<?> target, TraversalState state) {
      double perBlock = count == 0 ? cheapestCostPerBlock : sum / count;
      return from.distance(target.nearestBoundaryCell(from)) * perBlock;
    }

    @Override
    public void observe(double stepCost, double blocks) {
      if (blocks <= 0.0) {
        return;
      }
      double perBlock = stepCost / blocks;
      if (count < window.length) {
        window[next] = perBlock;
        sum += perBlock;
        count++;
      } else {
        sum += perBlock - window[next];
        window[next] = perBlock;
      }
      next = (next + 1) % window.length;
    }
  }
}
