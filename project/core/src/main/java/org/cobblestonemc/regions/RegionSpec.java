/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

/**
 * Everything that decides how one world's terrain is cut into regions: the terrain's rules, the
 * world's height, and the fill's detour ratio. Two columns built under equal specs are the same
 * graph; under different ones they are not, and must never meet in one search.
 *
 * <p><b>The geometry is the terrain's.</b> A column is {@code width} cells along X and Z, as the
 * terrain's own unit of reading is ({@link RegionTerrain#columnShift()}), and is stacked from cubic
 * sections of that size from {@link #columnMinY()} up. Cell {@code (x, y, z)} of a section is
 * {@code (y << 2·shift) | (z << shift) | x}; of a column, {@code y} counts from {@link
 * #columnMinY()}.
 *
 * @param <C> the platform's column of terrain
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
  private final int worldMinY;
  private final int worldMaxY;
  private final double detourRatio;
  private final int shift;
  private final int columnMinY;
  private final int sections;

  /**
   * Creates a spec.
   *
   * @param terrain what the cells are, and how they join
   * @param worldMinY the world's lowest Y, inclusive
   * @param worldMaxY the world's highest Y, inclusive
   * @param detourRatio how much longer than a straight line a walk inside one region may be; see
   *     {@link #DEFAULT_DETOUR_RATIO}
   */
  public RegionSpec(RegionTerrain<C> terrain, int worldMinY, int worldMaxY, double detourRatio) {
    this.terrain = terrain;
    this.worldMinY = worldMinY;
    this.worldMaxY = worldMaxY;
    this.detourRatio = detourRatio;
    this.shift = terrain.columnShift();
    if (shift < 1 || shift > 5) {
      // A section's cells are numbered in a short; 32³ is the most that leaves room for "none".
      throw new IllegalArgumentException("column shift " + shift + " is outside 1-5");
    }
    // Whole sections, so a section never straddles the world's floor or ceiling; cells outside the
    // world are classified as nothing.
    this.columnMinY = (worldMinY >> shift) << shift;
    this.sections = (worldMaxY >> shift) - (worldMinY >> shift) + 1;
  }

  /**
   * Creates a spec with the default detour ratio.
   *
   * @param terrain what the cells are, and how they join
   * @param worldMinY the world's lowest Y, inclusive
   * @param worldMaxY the world's highest Y, inclusive
   */
  public RegionSpec(RegionTerrain<C> terrain, int worldMinY, int worldMaxY) {
    this(terrain, worldMinY, worldMaxY, DEFAULT_DETOUR_RATIO);
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
   * Returns the world's lowest Y, inclusive.
   *
   * @return the floor
   */
  public int worldMinY() {
    return worldMinY;
  }

  /**
   * Returns the world's highest Y, inclusive.
   *
   * @return the ceiling
   */
  public int worldMaxY() {
    return worldMaxY;
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
   * Returns the base-2 log of a column's width, and of a section's every side.
   *
   * @return the shift
   */
  public int shift() {
    return shift;
  }

  /**
   * Returns how many cells wide a column is along X and Z, and how tall a section is.
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
   * Returns the lowest Y a column counts its cells from: the world's floor, rounded down to a
   * section.
   *
   * @return the column floor
   */
  public int columnMinY() {
    return columnMinY;
  }

  /**
   * Returns how many sections tall a column is.
   *
   * @return the section count
   */
  public int sections() {
    return sections;
  }

  /**
   * Returns how many cells tall a column is.
   *
   * @return the column height
   */
  public int height() {
    return sections << shift;
  }

  /**
   * Returns the column holding a world X or Z.
   *
   * @param coordinate the world X or Z
   * @return the column X or Z
   */
  public int columnOf(int coordinate) {
    return coordinate >> shift;
  }
}
