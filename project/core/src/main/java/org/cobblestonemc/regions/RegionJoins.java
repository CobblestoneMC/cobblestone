/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.util.function.IntFunction;

/**
 * How regions join, gathered into {@link RegionContacts}: faces that touch, and from a standing
 * cell a walking step a level up or down and a fall off a ledge.
 *
 * <p><b>One set of rules, inside a section and across a seam.</b> A {@link RegionSectionBuilder}
 * applies them between cells of the section it builds, and a {@link RegionGraph} between cells of
 * neighbouring sections. Both read regions through {@link Cells} in domain coordinates and classes
 * by region, so a ledge joins the same whichever side of a seam it is on.
 *
 * <p>Not thread-safe; one per builder or graph.
 */
final class RegionJoins {

  /** A cell the caller cannot see, such as one past the section being built. */
  static final int OUTSIDE = -2;

  /** Regions by cell, in the caller's numbering. */
  interface Cells {

    /**
     * Returns the region of a cell: {@link RegionSection#NONE} for none, or {@link #OUTSIDE} for a
     * cell these cells do not cover.
     *
     * @param x the X
     * @param y the Y
     * @param z the Z
     * @return the region
     */
    int regionAt(int x, int y, int z);
  }

  private final IntFunction<? extends RegionKind> classOf;
  private final int deepestFall;
  private final RegionContacts contacts = new RegionContacts();

  /**
   * Creates the rules for one domain.
   *
   * @param classOf a region's class, by region
   * @param deepestFall the deepest fall to look for a landing at
   */
  RegionJoins(IntFunction<? extends RegionKind> classOf, int deepestFall) {
    this.classOf = classOf;
    this.deepestFall = deepestFall;
  }

  /** Records that two cells of different regions share a face; see {@link RegionContacts#touch}. */
  void touch(int a, int b, int ax, int ay, int az, int bx, int by, int bz) {
    contacts.touch(a, b, ax, ay, az, bx, by, bz);
  }

  /**
   * Returns whether a region is one a body stands in, and so joins by steps and falls as well as
   * faces.
   *
   * @param region the region, or {@link RegionSection#NONE}
   * @return {@code true} if it is ground
   */
  boolean standing(int region) {
    return region >= 0 && classOf.apply(region).standing();
  }

  /**
   * Joins a standing region to what a body in it steps up into beside it: the cell a level above
   * {@code (tx, y, tz)}.
   */
  void stepUp(int from, int fx, int y, int fz, Cells cells, int tx, int tz) {
    step(from, cells.regionAt(tx, y + 1, tz), fx, y, fz, tx, y + 1, tz);
  }

  /**
   * Joins a standing region to what a body in it steps down into beside it: the cell a level below
   * {@code (tx, y, tz)}.
   */
  void stepDown(int from, int fx, int y, int fz, Cells cells, int tx, int tz) {
    step(from, cells.regionAt(tx, y - 1, tz), fx, y, fz, tx, y - 1, tz);
  }

  /** A walking step from a standing region into the region {@code to}, a level above or below. */
  private void step(int from, int to, int fx, int fy, int fz, int tx, int ty, int tz) {
    if (to >= 0 && to != from && classOf.apply(to).steppable()) {
      contacts.touch(from, to, fx, fy, fz, tx, ty, tz);
    }
  }

  /**
   * Finds where a body stepping off a ledge into the cell beside it, {@code (tx, y, tz)}, lands if
   * that cell is open, and joins the ledge to it, one way: the ledge reaches the ground, and the
   * ground does not reach the ledge.
   *
   * @return {@code true} if the fall left the cells: the landing is somewhere they do not cover
   */
  boolean drop(int from, int fx, int y, int fz, Cells cells, int tx, int tz) {
    int air = cells.regionAt(tx, y, tz);
    if (air < 0 || !classOf.apply(air).open()) {
      return false;
    }
    for (int k = 1; k <= deepestFall; k++) {
      int below = cells.regionAt(tx, y - k, tz);
      if (below == OUTSIDE) {
        return true;
      }
      if (below == RegionSection.NONE) {
        return false;
      }
      RegionKind kind = classOf.apply(below);
      if (kind.open()) {
        continue;
      }
      if (kind.landing(k)) {
        contacts.drop(from, below, k, fx, y, fz, tx, y, tz);
      }
      return false;
    }
    return false;
  }

  /** Emits everything gathered as portals and starts afresh; see {@link RegionContacts#flush}. */
  void flush(RegionContacts.Sink sink) {
    contacts.flush(sink);
  }
}
