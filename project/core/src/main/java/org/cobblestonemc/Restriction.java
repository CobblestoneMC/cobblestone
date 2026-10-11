/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * Bars cells from a search: no movement may enter a cell a restriction marks impassable.
 *
 * <p>The verdict is a {@link FutureOr} so a check may resolve asynchronously.
 *
 * @param <D> the domain type
 */
@FunctionalInterface
public interface Restriction<D extends Domain> {

  /**
   * Returns whether the given cell may not be entered.
   *
   * @param cell the candidate cell
   * @param domain the domain being traversed
   * @return {@code true} if the cell is impassable
   */
  FutureOr<Boolean> impassable(Cell cell, D domain);
}
