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
   * <p>This is the memory guard, and a cell is not cheap. A node carries a map of every candidate
   * parent that ever relaxed it and a set of its children (each retaining the {@code Movement} that
   * produced it), its cell-state, two map entries indexing it, and at least one entry on the open
   * set — which holds more entries than there are nodes, since superseded ones are left to be
   * skipped rather than removed. Call it the better part of a kilobyte per cell, so this default is
   * a couple of hundred megabytes for one solve that walks all the way into the cap.
   *
   * <p>Concurrent searches multiply that: the per-player search budget is a budget on searches, not
   * on heap. Lower this on a small heap.
   */
  public static final int DEFAULT_MAX_CELLS_VISITED = 200_000;

  /** Default wall-clock budget for the whole search, in milliseconds. */
  public static final long DEFAULT_MAX_WALL_CLOCK_MILLIS = 60_000L;

  /** Default pessimism factor applied to an unsolved Tier-1 leg's estimate. */
  public static final double DEFAULT_TIER1_UNSOLVED_PESSIMISM = 1.5;

  /** Default window width for the running-average heuristic. */
  public static final int DEFAULT_RUNNING_AVERAGE_WIDTH = 5;

  /**
   * Default A* heuristic weight (1.0 = admissible/optimal; &gt;1 = faster, weighted A*).
   *
   * <p>2.0 rather than 1.5, measured under {@link #DEFAULT_HEURISTIC}: for a survival player over
   * 22 routes it solves 18 rather than 15 and expands under half the nodes (geometric mean 4,009
   * against 9,241), for paths +5.4% over the best found against +1.6%. The coarse estimate sits
   * well below the true cost underground, so a lower weight leaves the search under-informed there
   * and it runs out of cells instead.
   */
  public static final double DEFAULT_HEURISTIC_WEIGHT = 2.0;

  /**
   * The estimate a search uses unless told otherwise.
   *
   * <p>{@link Heuristic#COARSE} by default, from measurement. Over 22 routes at {@link
   * #DEFAULT_HEURISTIC_WEIGHT}, a survival player's paths are +5.4% over the best found against the
   * running average's +19.2%, 18 routes solve against 16, and the search expands fewer nodes. With
   * a boat the gap is wider (+5.4% against +33.1%), because the running average never finds the
   * water worth reaching.
   *
   * <p>⚠️ It is not free: each solve profiles the terrain it reasons over, which on a long route is
   * thousands of chunk reads and seconds of CPU, and none of that is shared between searches yet. A
   * server that runs many concurrent searches on slow storage should measure before trusting the
   * default.
   */
  public static final Heuristic DEFAULT_HEURISTIC = Heuristic.COARSE;

  private final int maxCellsVisited;
  private final long maxWallClockMillis;
  private final double tier1UnsolvedPessimism;
  private final int runningAverageWidth;
  private final double heuristicWeight;
  private final Heuristic heuristic;

  private SearchSettings(Builder builder) {
    this.maxCellsVisited = builder.maxCellsVisited;
    this.maxWallClockMillis = builder.maxWallClockMillis;
    this.tier1UnsolvedPessimism = builder.tier1UnsolvedPessimism;
    this.runningAverageWidth = builder.runningAverageWidth;
    this.heuristicWeight = builder.heuristicWeight;
    this.heuristic = builder.heuristic;
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
   * <p>Tier-1 prices a leg it has not solved yet at straight-line distance times the cheapest cost
   * per block the agent could manage, corrected by how much dearer the legs it <i>has</i> solved in
   * that world actually turned out. This factor is the margin on top of that correction, for the
   * legs the correction has not seen; 1.0 takes the corrected estimate at face value.
   *
   * <p>It is deliberately small, because the correction does the real work — raising it makes
   * <i>every</i> unsolved leg look dearer, which biases the route toward whatever happened to be
   * solved first no matter what that cost. Raise it only if a search visibly re-plans too much
   * before its first solves have taught it anything.
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

  /**
   * Returns which estimate the fine search runs on.
   *
   * @return the heuristic
   */
  public Heuristic heuristic() {
    return heuristic;
  }

  /** Which estimate the fine search prices its remaining journey with. */
  public enum Heuristic {
    /**
     * Remaining distance times the per-block cost of the last few steps.
     *
     * <p>Cheap and needs nothing but the path already walked, but it extrapolates: it will happily
     * price a long journey at the rate of a river it is currently swimming down, because the river
     * really is cheap and simply does not go anywhere.
     */
    RUNNING_AVERAGE,

    /**
     * A search over 16-block terrain summaries, run backwards from the destination.
     *
     * <p>Prices the journey from terrain it has actually looked at rather than from terrain it has
     * just crossed. Costs chunk reads and CPU to build those summaries -- a long route touches
     * thousands of columns -- and each solve currently pays for its own.
     */
    COARSE
  }

  /** A fluent builder for {@link SearchSettings}. */
  public static final class Builder {

    private int maxCellsVisited = DEFAULT_MAX_CELLS_VISITED;
    private long maxWallClockMillis = DEFAULT_MAX_WALL_CLOCK_MILLIS;
    private double tier1UnsolvedPessimism = DEFAULT_TIER1_UNSOLVED_PESSIMISM;
    private int runningAverageWidth = DEFAULT_RUNNING_AVERAGE_WIDTH;
    private double heuristicWeight = DEFAULT_HEURISTIC_WEIGHT;
    private Heuristic heuristic = DEFAULT_HEURISTIC;

    private Builder() {}

    /**
     * Sets which estimate the fine search runs on.
     *
     * @param value the heuristic
     * @return this builder
     */
    public Builder heuristic(Heuristic value) {
      this.heuristic = java.util.Objects.requireNonNull(value, "heuristic");
      return this;
    }

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
