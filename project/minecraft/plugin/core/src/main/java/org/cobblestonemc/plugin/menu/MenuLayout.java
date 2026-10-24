/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.menu;

/** Where things sit in the navigation menu, a double chest of six rows of nine. */
public final class MenuLayout {

  /** Rows in the menu. */
  public static final int ROWS = 6;

  /** Slots in each row. */
  public static final int COLUMNS = 9;

  /** Slots in the menu. */
  public static final int SIZE = ROWS * COLUMNS;

  /** Entries across one row, inside the border. */
  public static final int CONTENT_COLUMNS = COLUMNS - 2;

  /** Rows of entries inside the border. */
  public static final int CONTENT_ROWS = ROWS - 2;

  /** The item naming where in the tree the player is. */
  public static final int BREADCRUMB = 4;

  /** The back button. */
  public static final int BACK = slot(ROWS - 1, 0);

  /** The previous-page button. */
  public static final int PREVIOUS = slot(ROWS - 1, 3);

  /** The close button. */
  public static final int CLOSE = slot(ROWS - 1, 4);

  /** The next-page button. */
  public static final int NEXT = slot(ROWS - 1, 5);

  /** Where a level with no entries says so. */
  public static final int EMPTY = slot(2, 4);

  private MenuLayout() {}

  /**
   * The slot at a row and column.
   *
   * @param row the row, from the top
   * @param column the column, from the left
   * @return the slot index
   */
  public static int slot(int row, int column) {
    return row * COLUMNS + column;
  }

  /**
   * The slot of the {@code index}th entry inside the border, counting from the {@code firstRow}th
   * row of entries.
   *
   * @param firstRow the row of entries to start at (0 is the first inside the border)
   * @param index the entry's position, left to right then top to bottom
   * @return the slot index
   */
  static int content(int firstRow, int index) {
    return slot(1 + firstRow + index / CONTENT_COLUMNS, 1 + index % CONTENT_COLUMNS);
  }
}
