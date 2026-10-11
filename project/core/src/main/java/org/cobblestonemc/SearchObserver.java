/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.List;

/**
 * A window into what a solve did, for recording and visualization.
 *
 * <p>Every method does nothing by default and the search holds {@link #none()} unless something
 * asks otherwise, so production pays a call to an empty method on a monomorphic call site — which
 * the JIT removes. Nothing here may affect the search: an observer that threw, blocked, or mutated
 * anything it was handed would change the thing it is supposed to be watching.
 *
 * <p><b>Recording is not free, and is not meant to be on during a timed run.</b> A long solve
 * expands hundreds of thousands of nodes, so a recorder writing an event per expansion produces a
 * few megabytes and costs real time. The intended shape is: benchmark with {@link #none()}, then
 * re-run the interesting scenario with a recorder and look at what it did. Keeping the two runs
 * separate is what stops the measurement and the explanation from interfering with each other.
 *
 * <p>Calls may arrive on any thread, though a single solve makes them from inside its own
 * single-flight pump, so they are ordered with respect to one another.
 */
public interface SearchObserver {

  /** Why a cell stopped being part of the search. */
  enum RemovalReason {
    /** A restriction verdict came back saying the cell is impassable after all. */
    RESTRICTED,

    /** The route through it was invalidated and it was re-costed or dropped during repair. */
    REPAIRED
  }

  /**
   * The observer that records nothing.
   *
   * <p>A constant rather than a fresh instance per call, for two reasons: every solve that is not
   * being recorded then shares one object, so the call sites in the search stay monomorphic and the
   * JIT can inline the empty bodies away; and {@code observer == none()} becomes a usable test for
   * "nobody is watching", which a new-instance factory would quietly make always false.
   */
  SearchObserver NONE = new SearchObserver() {};

  /**
   * Returns the observer that records nothing.
   *
   * @return {@link #NONE}
   */
  static SearchObserver none() {
    return NONE;
  }

  /**
   * Called when a cell is placed on the open set, or re-placed at a better cost.
   *
   * @param cell the cell
   * @param cost the cost to reach it
   * @param estimatedTotalCost its {@code f}
   */
  default void opened(Cell cell, double cost, double estimatedTotalCost) {}

  /**
   * Called when a cell is popped and settled.
   *
   * @param cell the cell
   * @param cost the cost to reach it
   * @param estimatedTotalCost its {@code f}
   */
  default void closed(Cell cell, double cost, double estimatedTotalCost) {}

  /**
   * Called when the solve parks because a mode is waiting on blocks it does not have.
   *
   * <p>Paired with {@link #resumed()}. The split between active and parked time is most of what
   * distinguishes a search that is thinking too hard from one that is waiting too long.
   */
  default void parked() {}

  /** Called when the solve wakes from a park. */
  default void resumed() {}

  /**
   * Called when a cell is taken back out of the search.
   *
   * @param cell the cell
   * @param reason why
   */
  default void removed(Cell cell, RemovalReason reason) {}

  /**
   * Called when a solve finishes with a path.
   *
   * @param cells the path, origin first
   */
  default void solved(List<Cell> cells) {}

  /**
   * Called when a solve finishes without one.
   *
   * @param outcome why it gave up
   */
  default void failed(String outcome) {}
}
