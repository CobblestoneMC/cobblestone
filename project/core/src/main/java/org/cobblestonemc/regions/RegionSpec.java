/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import org.cobblestonemc.DomainBounds;

/**
 * Everything that decides how one domain's terrain is cut into regions: the terrain's rules, the
 * domain's bounds, and the fill's detour ratio. Two stacks built under equal specs are the same
 * graph; under different ones they are not, and must never meet in one search.
 *
 * <p><b>The geometry is the terrain's and the domain's.</b> Terrain is built in cubic sections, as
 * wide as the terrain says ({@link RegionTerrain#sectionShift()}). A <em>stack</em> is the sections
 * at one X and Z from the domain's floor to its ceiling, which is why the domain must bound Y, the
 * axis bodies fall along: a stack is the unit terrain is read, built, kept and joined in. Cell
 * {@code (x, y, z)} of a section is {@code (y << 2·shift) | (z << shift) | x}; of a stack, {@code
 * y} counts from {@link #stackMinY()}. Bounds along X or Z, where a domain has them, leave a
 * stack's shape alone: cells outside them belong to no region.
 *
 * @param <C> the platform's stack of terrain
 */
public final class RegionSpec<C> {

  /**
   * How much longer than a straight line the walk from a region's seed to one of its cells may be,
   * by default.
   *
   * <p>A region is priced as a straight line, so this is the most it can under-price a crossing by:
   * half again, plus a few blocks of slack. A cave bending back on itself splits into two regions
   * joined at the bend, and the bend is then where the route is priced through.
   */
  public static final double DEFAULT_DETOUR_RATIO = 1.5;

  private final RegionTerrain<C> terrain;
  private final DomainBounds bounds;
  private final double detourRatio;
  private final int shift;
  private final int stackMinY;
  private final int sections;

  /**
   * Creates a spec.
   *
   * @param terrain what the cells are, and how they join
   * @param bounds the cells the domain holds; it must bound Y
   * @param detourRatio how much longer than a straight line a walk inside one region may be; see
   *     {@link #DEFAULT_DETOUR_RATIO}
   * @throws IllegalArgumentException if the domain does not bound Y
   */
  public RegionSpec(RegionTerrain<C> terrain, DomainBounds bounds, double detourRatio) {
    if (!bounds.bounded(DomainBounds.Y)) {
      // Lifting this means joining stacks above and below each other, falls included.
      throw new IllegalArgumentException(
          "regions stack sections along Y, the axis bodies fall along, so the domain must bound"
              + " it: "
              + bounds);
    }
    this.terrain = terrain;
    this.bounds = bounds;
    this.detourRatio = detourRatio;
    this.shift = terrain.sectionShift();
    if (shift < 1 || shift > 5) {
      // A section's cells are numbered in a short; 32³ is the most that leaves room for "none".
      throw new IllegalArgumentException("section shift " + shift + " is outside 1-5");
    }
    // Whole sections, so a section never straddles the domain's floor or ceiling; cells outside
    // the domain are classified as nothing.
    this.stackMinY = (bounds.minY() >> shift) << shift;
    this.sections = (bounds.maxY() >> shift) - (bounds.minY() >> shift) + 1;
  }

  /**
   * Creates a spec with the default detour ratio.
   *
   * @param terrain what the cells are, and how they join
   * @param bounds the cells the domain holds; it must bound Y
   */
  public RegionSpec(RegionTerrain<C> terrain, DomainBounds bounds) {
    this(terrain, bounds, DEFAULT_DETOUR_RATIO);
  }

  /**
   * Returns what the cells are, and how they join.
   *
   * @return the terrain
   */
  public RegionTerrain<C> terrain() {
    return terrain;
  }

  /**
   * Returns the cells the domain holds.
   *
   * @return the bounds
   */
  public DomainBounds bounds() {
    return bounds;
  }

  /**
   * Returns how much longer than a straight line a walk inside one region may be.
   *
   * @return the ratio
   */
  public double detourRatio() {
    return detourRatio;
  }

  /**
   * Returns the base-2 log of a stack's width, and of a section's every side.
   *
   * @return the shift
   */
  public int shift() {
    return shift;
  }

  /**
   * Returns how many cells wide a stack is along X and Z, and how tall a section is.
   *
   * @return the width
   */
  public int width() {
    return 1 << shift;
  }

  /**
   * Returns how many cells a section holds.
   *
   * @return the section's volume
   */
  public int sectionVolume() {
    return 1 << (3 * shift);
  }

  /**
   * Returns the lowest Y a stack counts its cells from: the domain's floor, rounded down to a
   * section.
   *
   * @return the stack floor
   */
  public int stackMinY() {
    return stackMinY;
  }

  /**
   * Returns how many sections tall a stack is.
   *
   * @return the section count
   */
  public int sections() {
    return sections;
  }

  /**
   * Returns how many cells tall a stack is.
   *
   * @return the stack height
   */
  public int height() {
    return sections << shift;
  }

  /**
   * Returns the stack holding an X or Z coordinate.
   *
   * @param coordinate the X or Z
   * @return the stack X or Z
   */
  public int stackOf(int coordinate) {
    return coordinate >> shift;
  }
}
