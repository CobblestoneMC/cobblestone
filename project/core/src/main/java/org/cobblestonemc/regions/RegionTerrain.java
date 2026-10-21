/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

/**
 * What a platform's terrain is, for the region layer: which class each cell belongs to, and how
 * cells of different classes join.
 *
 * <p>The region layer itself knows nothing of any game. It groups cells of one class into regions,
 * joins touching regions with portals, and searches over them; everything that depends on what the
 * cells actually are comes from here. A class is a small number the terrain defines, and what
 * crossing one costs is a {@link RegionAgent}'s answer, never the terrain's.
 *
 * <p><b>The joining rules are movement rules, under gravity.</b> Y is up, and a body falls toward
 * lower Y. Cells that touch face to face are always joined. Beyond that a body that stands can step
 * a level up or down, and can drop off a ledge onto something below, which joins cells that do not
 * touch and joins them one way. The terrain says which classes take part.
 *
 * <p><b>Classes are small numbers the terrain chooses,</b> from 0 up to {@link #classes()}, with
 * gaps allowed: a terrain that retires a class should retire its number with it, rather than
 * renumber the others.
 *
 * <p>Must be safe to call from several threads at once: builds run in parallel.
 *
 * @param <C> the platform's column of terrain, such as a chunk
 */
public interface RegionTerrain<C> {

  /**
   * Returns the version of these rules. Columns stored under any other version are built again:
   * read back, they would mix two models in one search.
   *
   * @return the version
   */
  int version();

  /**
   * Returns one more than the highest class number. Classes are numbered from 0, and fewer than
   * 128.
   *
   * @return the bound on class numbers
   */
  int classes();

  /**
   * Returns the base-2 log of how wide a column of this terrain is along X and Z: 4 for a column 16
   * cells wide. The region layer's sections are cubes of that size.
   *
   * @return the shift, from 1 to 5
   */
  int columnShift();

  /**
   * Classifies every cell of a column.
   *
   * <p>With {@code s} the {@link #columnShift()}, cell {@code (x, y, z)} of the column, with {@code
   * y} counted from {@code columnMinY}, is {@code classes[(y << 2s) | (z << s) | x]}: its class, or
   * {@code -1} for a cell that belongs to no region, such as one outside the world or one nothing
   * can enter or break.
   *
   * @param column the column
   * @param worldMinY the world's lowest Y, inclusive
   * @param worldMaxY the world's highest Y, inclusive
   * @param columnMinY the Y the column's cells count from, at or below {@code worldMinY}
   * @param classes where to write each cell's class; its length is a multiple of 256
   */
  void classify(C column, int worldMinY, int worldMaxY, int columnMinY, byte[] classes);

  /**
   * Returns whether a body stands in cells of this class, so that steps a level up or down join
   * them as well as faces.
   *
   * <p>A standing class must never fill a whole section: the builder skips the inside of a section
   * that is one class edge to edge, and would miss the steps down out of its bottom layer. Standing
   * means something solid underfoot, so for any terrain under gravity this holds by itself.
   *
   * @param klass the class
   * @return {@code true} if it is ground
   */
  boolean standing(int klass);

  /**
   * Returns whether a standing body can step into a cell of this class a level up or down.
   *
   * @param klass the class
   * @return {@code true} if it can
   */
  boolean steppable(int klass);

  /**
   * Returns whether a body falls through cells of this class.
   *
   * @param klass the class
   * @return {@code true} for open space
   */
  boolean open(int klass);

  /**
   * Returns whether a fall of {@code depth} blocks onto this class is worth a portal.
   *
   * @param klass the class landed on
   * @param depth how far the body falls, at least 1
   * @return {@code true} if the fall joins the ledge to it
   */
  boolean landing(int klass, int depth);

  /**
   * Returns the deepest fall worth looking for a landing at.
   *
   * @return the depth, in cells
   */
  int deepestFall();

  /**
   * Returns the family a class fills with: cells of classes in one family fill into one region,
   * which {@link #label} then gives a class from the mix.
   *
   * <p>For terrain that comes in kinds an agent prices differently but that are mixed finely
   * together, such as two kinds of rock dithered into each other, where a region per kind would
   * shred both into fragments. By default every class is a family of its own.
   *
   * @param klass the class
   * @return the family, itself a class
   */
  default int family(int klass) {
    return klass;
  }

  /**
   * Returns the class of a region filled from a family, given how many of its cells are of each
   * class.
   *
   * <p>By default, the class most of its cells are.
   *
   * @param family the family the region was filled from
   * @param counts how many of the region's cells are of each class, by class
   * @return the region's class
   */
  default int label(int family, int[] counts) {
    int best = family;
    for (int klass = 0; klass < counts.length; klass++) {
      if (counts[klass] > counts[best]) {
        best = klass;
      }
    }
    return best;
  }

  /**
   * Returns whether regions of this class must be close to convex.
   *
   * <p>A region is priced in straight lines, so the fill refuses a cell whose walk from the
   * region's seed is much longer than the straight line. A class crossed in straight lines whatever
   * its shape, such as rock that is dug through, need not be.
   *
   * @param klass the class
   * @return {@code true} if the fill caps the detour
   */
  boolean convex(int klass);
}
