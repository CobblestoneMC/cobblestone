/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

/**
 * Immutable, tunable limits and knobs for a single search.
 *
 * <p>Only the numeric knobs live here for now; the pluggable heuristic strategy is selected in the
 * {@code core} module (Phase 2), so it is not part of this API surface yet. Build instances with
 * {@link #builder()} or take {@link #defaults()}.
 */
public final class SearchSettings {

  /** Default cap on cells visited within a single Tier-2 A* solve. */
  public static final int DEFAULT_MAX_CELLS_VISITED = 10_000;

  /** Default wall-clock budget for the whole search, in milliseconds. */
  public static final long DEFAULT_MAX_WALL_CLOCK_MILLIS = 60_000L;

  /** Default Tier-1 recalculation overshoot threshold (1.30 = re-plan at 30% over estimate). */
  public static final double DEFAULT_TIER1_RECALC_THRESHOLD = 1.30;

  /** Default window width for the running-average heuristic. */
  public static final int DEFAULT_RUNNING_AVERAGE_WIDTH = 5;

  /** Default A* heuristic weight (1.0 = admissible/optimal; &gt;1 = faster, weighted A*). */
  public static final double DEFAULT_HEURISTIC_WEIGHT = 1.0;

  private final int maxCellsVisited;
  private final long maxWallClockMillis;
  private final double tier1RecalcThreshold;
  private final int runningAverageWidth;
  private final double heuristicWeight;

  private SearchSettings(Builder builder) {
    this.maxCellsVisited = builder.maxCellsVisited;
    this.maxWallClockMillis = builder.maxWallClockMillis;
    this.tier1RecalcThreshold = builder.tier1RecalcThreshold;
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
   * Returns the Tier-1 recalculation overshoot threshold.
   *
   * @return the recalc threshold
   */
  public double tier1RecalcThreshold() {
    return tier1RecalcThreshold;
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
    private double tier1RecalcThreshold = DEFAULT_TIER1_RECALC_THRESHOLD;
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
     * Sets the Tier-1 recalculation overshoot threshold.
     *
     * @param value the threshold (must be &gt;= 1.0)
     * @return this builder
     */
    public Builder tier1RecalcThreshold(double value) {
      if (value < 1.0) {
        throw new IllegalArgumentException("tier1RecalcThreshold must be >= 1.0: " + value);
      }
      this.tier1RecalcThreshold = value;
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
