/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import org.cobblestonemc.DomainBounds;

/**
 * What a platform's terrain is, for the region layer: which class each cell belongs to.
 *
 * <p>The region layer itself knows nothing of any game. It groups cells of one class into regions,
 * joins touching regions with portals, and searches over them; everything that depends on what the
 * cells actually are comes from here, and how each class joins from the class itself ({@link
 * RegionKind}). What crossing a class costs is a {@link RegionAgent}'s answer, never the terrain's.
 *
 * <p><b>Classes are an enum,</b> of fewer than 128 constants. A region stores its class by ordinal,
 * so {@link RegionFiles} keeps the enum's names with what it stores, and reads nothing back stored
 * under others.
 *
 * <p><b>Change how cells are classified, bump {@link #version()}.</b> A built section is a fact
 * about the rules it was built by; one built under others is a different graph.
 *
 * <p>Must be safe to call from several threads at once: builds run in parallel.
 *
 * @param <C> the platform's terrain, as a {@link TerrainSource} reads it
 * @param <K> the terrain's classes
 */
public interface RegionTerrain<C, K extends Enum<K> & RegionKind> {

  /**
   * Returns the version of these rules: how cells are classified. Sections stored under any other
   * version are built again: read back, they would mix two models in one search. The classes
   * themselves need no bump, since a stored section is kept with their names.
   *
   * @return the version
   */
  int version();

  /**
   * Returns the terrain's classes.
   *
   * @return the enum of classes
   */
  Class<K> classes();

  /**
   * Returns the base-2 log of a section's side: 4 for sections of 16³ cells.
   *
   * @return the shift, from 1 to 5
   */
  int sectionShift();

  /**
   * Classifies every cell of a section.
   *
   * <p>With {@code s} the {@link #sectionShift()}, cell {@code (originX + x, originY + y, originZ +
   * z)} is {@code cells[(y << 2s) | (z << s) | x]}: its class, or {@code null} for a cell that
   * belongs to no region, such as one nothing can enter or break.
   *
   * <p><b>A cell's class may read the cells directly above and below it, and nothing farther.</b> A
   * block change is forgotten from the sections holding the cells it can change, which are its own
   * and the ones over and under it. Cells outside {@code bounds} are not the domain's: they belong
   * to no region and are not to be read.
   *
   * @param terrain the terrain the section is read from
   * @param originX the section's lowest X
   * @param originY the section's lowest Y
   * @param originZ the section's lowest Z
   * @param bounds the cells the domain holds
   * @param cells where to write each cell's class
   */
  void classify(C terrain, int originX, int originY, int originZ, DomainBounds bounds, K[] cells);

  /**
   * Returns the deepest fall to look for a landing at: deeper falls are no portal for any agent.
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
   * @param clazz the class
   * @return the family, itself a class
   */
  default K family(K clazz) {
    return clazz;
  }

  /**
   * Returns the class of a region filled from a family, given how many of its cells are of each
   * class.
   *
   * <p>By default, the class most of its cells are.
   *
   * @param family the family the region was filled from
   * @param counts how many of the region's cells are of each class, by the class's ordinal
   * @return the region's class
   */
  default K label(K family, int[] counts) {
    K[] classes = classes().getEnumConstants();
    K best = family;
    for (K clazz : classes) {
      if (counts[clazz.ordinal()] > counts[best.ordinal()]) {
        best = clazz;
      }
    }
    return best;
  }
}
