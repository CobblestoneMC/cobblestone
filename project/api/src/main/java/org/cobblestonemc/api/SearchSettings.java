/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

/**
 * Immutable, tunable limits and knobs for a single search.
 *
 * <p>Only the numeric knobs live here for now; the pluggable heuristic strategy is selected in the
 * {@code core} module (Phase 2), so it is not part of this API surface yet. Build instances with
 * {@link #builder()} or take {@link #defaults()}.
 */
public final class SearchSettings {

  /**
   * Default cap on cells visited within a single Tier-2 A* solve.
   *
   * <p>This is the memory guard: a solve holds one node per cell it reaches, so the cap bounds the
   * search tree at roughly a few hundred bytes a cell — a few hundred megabytes at this default.
   * Lower it on a small heap.
   */
  public static final int DEFAULT_MAX_CELLS_VISITED = 1_000_000;

  /** Default wall-clock budget for the whole search, in milliseconds. */
  public static final long DEFAULT_MAX_WALL_CLOCK_MILLIS = 60_000L;

  /** Default pessimism factor applied to an unsolved Tier-1 leg's estimate. */
  public static final double DEFAULT_TIER1_UNSOLVED_PESSIMISM = 5.0;

  /** Default window width for the running-average heuristic. */
  public static final int DEFAULT_RUNNING_AVERAGE_WIDTH = 5;

  /** Default A* heuristic weight (1.0 = admissible/optimal; &gt;1 = faster, weighted A*). */
  public static final double DEFAULT_HEURISTIC_WEIGHT = 1.5;

  private final int maxCellsVisited;
  private final long maxWallClockMillis;
  private final double tier1UnsolvedPessimism;
  private final int runningAverageWidth;
  private final double heuristicWeight;

  private SearchSettings(Builder builder) {
    this.maxCellsVisited = builder.maxCellsVisited;
    this.maxWallClockMillis = builder.maxWallClockMillis;
    this.tier1UnsolvedPessimism = builder.tier1UnsolvedPessimism;
    this.runningAverageWidth = builder.runningAverageWidth;
    this.heuristicWeight = builder.heuristicWeight;
  }

  /**
   * Returns settings with every knob at its default.
   *
   * @return the default settings
   */
  public static SearchSettings defaults() {
    return builder().build();
  }

  /**
   * Returns a new builder pre-filled with defaults.
   *
   * @return a builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the cap on cells visited within a single Tier-2 A* solve.
   *
   * @return the max cells visited
   */
  public int maxCellsVisited() {
    return maxCellsVisited;
  }

  /**
   * Returns the wall-clock budget for the whole search, in milliseconds.
   *
   * @return the max wall-clock time
   */
  public long maxWallClockMillis() {
    return maxWallClockMillis;
  }

  /**
   * Returns the pessimism factor applied to an unsolved Tier-1 leg's cost estimate.
   *
   * <p>Tier-1 prices a leg it has not solved yet with an admissible lower bound — straight-line
   * distance at the cheapest cost per block — which real terrain beats by a wide margin. Taken at
   * face value, every leg comes back several times dearer than promised, which makes some other
   * unexplored route look cheaper, so Tier-1 re-plans and works through the alternatives one costly
   * solve at a time. Scaling the estimate up brings it nearer what a leg actually costs, at the
   * price of a coarse route chosen on a less optimistic bound. 1.0 leaves the bound untouched.
   *
   * @return the unsolved-leg pessimism factor
   */
  public double tier1UnsolvedPessimism() {
    return tier1UnsolvedPessimism;
  }

  /**
   * Returns the window width for the running-average heuristic.
   *
   * @return the running-average width
   */
  public int runningAverageWidth() {
    return runningAverageWidth;
  }

  /**
   * Returns the A* heuristic weight applied in Tier-2 (1.0 = admissible; &gt;1 trades optimality
   * for a smaller explored frontier — weighted A*).
   *
   * @return the heuristic weight
   */
  public double heuristicWeight() {
    return heuristicWeight;
  }

  /** A fluent builder for {@link SearchSettings}. */
  public static final class Builder {

    private int maxCellsVisited = DEFAULT_MAX_CELLS_VISITED;
    private long maxWallClockMillis = DEFAULT_MAX_WALL_CLOCK_MILLIS;
    private double tier1UnsolvedPessimism = DEFAULT_TIER1_UNSOLVED_PESSIMISM;
    private int runningAverageWidth = DEFAULT_RUNNING_AVERAGE_WIDTH;
    private double heuristicWeight = DEFAULT_HEURISTIC_WEIGHT;

    private Builder() {}

    /**
     * Sets the A* heuristic weight (must be &gt;= 1.0).
     *
     * @param value the weight
     * @return this builder
     */
    public Builder heuristicWeight(double value) {
      if (value < 1.0) {
        throw new IllegalArgumentException("heuristicWeight must be >= 1.0: " + value);
      }
      this.heuristicWeight = value;
      return this;
    }

    /**
     * Sets the cap on cells visited within a single Tier-2 A* solve.
     *
     * @param value the max cells visited (must be positive)
     * @return this builder
     */
    public Builder maxCellsVisited(int value) {
      this.maxCellsVisited = requirePositive(value, "maxCellsVisited");
      return this;
    }

    /**
     * Sets the wall-clock budget for the whole search, in milliseconds.
     *
     * @param value the budget in milliseconds (must be positive)
     * @return this builder
     */
    public Builder maxWallClockMillis(long value) {
      if (value <= 0) {
        throw new IllegalArgumentException("maxWallClockMillis must be positive: " + value);
      }
      this.maxWallClockMillis = value;
      return this;
    }

    /**
     * Sets the pessimism factor applied to an unsolved Tier-1 leg's cost estimate; see {@link
     * SearchSettings#tier1UnsolvedPessimism()}.
     *
     * @param value the factor (must be &gt;= 1.0)
     * @return this builder
     */
    public Builder tier1UnsolvedPessimism(double value) {
      if (value < 1.0) {
        throw new IllegalArgumentException("tier1UnsolvedPessimism must be >= 1.0: " + value);
      }
      this.tier1UnsolvedPessimism = value;
      return this;
    }

    /**
     * Sets the window width for the running-average heuristic.
     *
     * @param value the width (must be positive)
     * @return this builder
     */
    public Builder runningAverageWidth(int value) {
      this.runningAverageWidth = requirePositive(value, "runningAverageWidth");
      return this;
    }

    /**
     * Builds the immutable settings.
     *
     * @return the settings
     */
    public SearchSettings build() {
      return new SearchSettings(this);
    }

    private static int requirePositive(int value, String name) {
      if (value <= 0) {
        throw new IllegalArgumentException(name + " must be positive: " + value);
      }
      return value;
    }
  }
}
