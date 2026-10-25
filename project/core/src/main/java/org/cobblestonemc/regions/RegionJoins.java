/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.util.function.IntUnaryOperator;

/**
 * How regions join, gathered into {@link RegionContacts}: faces that touch, and from a standing
 * cell a walking step a level up or down and a fall off a ledge.
 *
 * <p><b>One set of rules, inside a column and across a seam.</b> A {@link RegionColumnBuilder}
 * applies them between neighbouring cells of the column it builds, and a {@link RegionGraph}
 * between the edge cells of two columns. Both read regions through {@link Cells} and classes by
 * region, so a ledge joins the same whichever side of a seam it is on.
 *
 * <p>Not thread-safe; one per builder or graph.
 */
final class RegionJoins {

  /** Regions by cell within one column, in the caller's numbering. */
  interface Cells {

    /**
     * Returns the region of a cell, or {@link RegionColumn#NONE}.
     *
     * @param lx the X within the column
     * @param ly the Y counted from the column's floor
     * @param lz the Z within the column
     * @return the region
     */
    int regionAt(int lx, int ly, int lz);
  }

  private final RegionTerrain<?> terrain;
  private final IntUnaryOperator classOf;
  private final int height;
  private final RegionContacts contacts = new RegionContacts();

  /**
   * Creates the rules for one world.
   *
   * @param terrain what the classes mean
   * @param classOf a region's class, by region
   * @param height how many cells tall a column is
   */
  RegionJoins(RegionTerrain<?> terrain, IntUnaryOperator classOf, int height) {
    this.terrain = terrain;
    this.classOf = classOf;
    this.height = height;
  }

  /** Records that two cells of different regions share a face; see {@link RegionContacts#touch}. */
  void touch(int a, int b, int ax, int ay, int az, int bx, int by, int bz) {
    contacts.touch(a, b, ax, ay, az, bx, by, bz);
  }

  /**
   * Returns whether a region is one a body stands in, and so joins by steps and falls as well as
   * faces.
   *
   * @param region the region, not {@link RegionColumn#NONE}
   * @return {@code true} if it is ground
   */
  boolean standing(int region) {
    return terrain.standing(classOf.applyAsInt(region));
  }

  /**
   * Joins a standing region to what a body in it can step or drop into beside it.
   *
   * <p>The cell beside is {@code (lx, ly, lz)} of {@code beside}, on the same level as the standing
   * cell: a step reaches a level above or below it, and a fall starts in it when it is open.
   *
   * @param from the standing region
   * @param fx the standing cell's world X
   * @param y the standing cell's world Y
   * @param fz the standing cell's world Z
   * @param beside the column the cell beside is in
   * @param lx the cell beside, X within its column
   * @param ly the cell beside, Y counted from the column's floor
   * @param lz the cell beside, Z within its column
   * @param tx the cell beside's world X
   * @param tz the cell beside's world Z
   */
  void beside(
      int from, int fx, int y, int fz, Cells beside, int lx, int ly, int lz, int tx, int tz) {
    if (ly + 1 < height) {
      step(from, beside.regionAt(lx, ly + 1, lz), fx, y, fz, tx, y + 1, tz);
    }
    if (ly > 0) {
      step(from, beside.regionAt(lx, ly - 1, lz), fx, y, fz, tx, y - 1, tz);
    }
    int air = beside.regionAt(lx, ly, lz);
    if (air != RegionColumn.NONE && terrain.open(classOf.applyAsInt(air))) {
      drop(from, fx, y, fz, beside, lx, ly, lz, tx, tz);
    }
  }

  /** A walking step from a standing region into the region {@code to}, a level above or below. */
  private void step(int from, int to, int fx, int fy, int fz, int tx, int ty, int tz) {
    if (to != RegionColumn.NONE && to != from && terrain.steppable(classOf.applyAsInt(to))) {
      contacts.touch(from, to, fx, fy, fz, tx, ty, tz);
    }
  }

  /**
   * Finds where a body stepping off a ledge into the open cell {@code (lx, ly, lz)} lands, and
   * joins the ledge to it, one way: the ledge reaches the ground, and the ground does not reach the
   * ledge.
   */
  private void drop(
      int from, int fx, int y, int fz, Cells beside, int lx, int ly, int lz, int tx, int tz) {
    for (int k = 1; k <= terrain.deepestFall() && ly - k >= 0; k++) {
      int below = beside.regionAt(lx, ly - k, lz);
      if (below == RegionColumn.NONE) {
        return;
      }
      int clazz = classOf.applyAsInt(below);
      if (terrain.open(clazz)) {
        continue;
      }
      if (terrain.landing(clazz, k)) {
        contacts.drop(from, below, k, fx, y, fz, tx, y, tz);
      }
      return;
    }
  }

  /** Emits everything gathered as portals and starts afresh; see {@link RegionContacts#flush}. */
  void flush(RegionContacts.Sink sink) {
    contacts.flush(sink);
  }
}
