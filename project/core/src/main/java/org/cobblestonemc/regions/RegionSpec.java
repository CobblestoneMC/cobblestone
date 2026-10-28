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
 * domain's bounds, and the fill's detour ratio. Two sections built under equal specs are the same
 * graph; under different ones they are not, and must never meet in one search.
 *
 * <p><b>The geometry is the terrain's.</b> Terrain is built in cubic sections, as wide as the
 * terrain says ({@link RegionTerrain#sectionShift()}), on a grid from the origin. Cell {@code (x,
 * y, z)} of a section is {@code (y << 2·shift) | (z << shift) | x}. The domain's bounds, along any
 * axis or none, only say which cells are its: cells past them belong to no region, and a section
 * with no cell inside them is never read.
 *
 * @param <C> the platform's terrain, as a {@link TerrainSource} reads it
 * @param <K> the terrain's classes
 */
public final class RegionSpec<C, K extends Enum<K> & RegionKind> {

  /**
   * How much longer than a straight line the walk from a region's seed to one of its cells may be,
   * by default.
   *
   * <p>A region is priced as a straight line, so this is the most it can under-price a crossing by:
   * half again, plus a few blocks of slack. A cave bending back on itself splits into two regions
   * joined at the bend, and the bend is then where the route is priced through.
   */
  public static final double DEFAULT_DETOUR_RATIO = 1.5;

  private final RegionTerrain<C, K> terrain;
  private final K[] classes;
  private final DomainBounds bounds;
  private final double detourRatio;
  private final int shift;
  private final int fallSections;

  /**
   * Creates a spec.
   *
   * @param terrain what the cells are
   * @param bounds the cells the domain holds
   * @param detourRatio how much longer than a straight line a walk inside one region may be; see
   *     {@link #DEFAULT_DETOUR_RATIO}
   */
  public RegionSpec(RegionTerrain<C, K> terrain, DomainBounds bounds, double detourRatio) {
    this.terrain = terrain;
    this.classes = terrain.classes().getEnumConstants();
    if (classes.length > Byte.MAX_VALUE) {
      // A region keeps its class in a byte, and -1 is "none".
      throw new IllegalArgumentException(classes.length + " classes is more than 127");
    }
    this.bounds = bounds;
    this.detourRatio = detourRatio;
    this.shift = terrain.sectionShift();
    if (shift < 1 || shift > 5) {
      // A section's cells are numbered in a short; 32³ is the most that leaves room for "none".
      throw new IllegalArgumentException("section shift " + shift + " is outside 1-5");
    }
    this.fallSections = (terrain.deepestFall() + width() - 1) >> shift;
  }

  /**
   * Creates a spec with the default detour ratio.
   *
   * @param terrain what the cells are
   * @param bounds the cells the domain holds
   */
  public RegionSpec(RegionTerrain<C, K> terrain, DomainBounds bounds) {
    this(terrain, bounds, DEFAULT_DETOUR_RATIO);
  }

  /**
   * Returns what the cells are.
   *
   * @return the terrain
   */
  public RegionTerrain<C, K> terrain() {
    return terrain;
  }

  /**
   * Returns the class a region stores as a number.
   *
   * @param stored the class's ordinal
   * @return the class
   */
  K classOf(int stored) {
    return classes[stored];
  }

  /**
   * Returns how many classes the terrain has.
   *
   * @return the class count
   */
  int classCount() {
    return classes.length;
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
   * Returns the base-2 log of a section's side.
   *
   * @return the shift
   */
  public int shift() {
    return shift;
  }

  /**
   * Returns how many cells a section's side is.
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
   * Returns the section holding a coordinate, along any axis.
   *
   * @param coordinate the X, Y or Z
   * @return the section's X, Y or Z
   */
  public int sectionOf(int coordinate) {
    return coordinate >> shift;
  }

  /**
   * Returns how many sections below its own a fall can land in: the terrain's deepest fall, in
   * sections, rounded up.
   *
   * @return the section count
   */
  int fallSections() {
    return fallSections;
  }

  /**
   * Returns whether a section holds no cell of the domain, and so has no regions to read.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @return {@code true} if it is wholly outside
   */
  boolean outside(int sectionX, int sectionY, int sectionZ) {
    return beyond(sectionX, bounds.minX(), bounds.maxX())
        || beyond(sectionY, bounds.minY(), bounds.maxY())
        || beyond(sectionZ, bounds.minZ(), bounds.maxZ());
  }

  private boolean beyond(int section, int min, int max) {
    long low = (long) section << shift;
    return low + width() - 1 < min || low > max;
  }

  /**
   * Returns whether a section holds a cell outside the domain.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @return {@code true} if the domain's edge crosses it
   */
  boolean straddles(int sectionX, int sectionY, int sectionZ) {
    return crosses(sectionX, bounds.minX(), bounds.maxX())
        || crosses(sectionY, bounds.minY(), bounds.maxY())
        || crosses(sectionZ, bounds.minZ(), bounds.maxZ());
  }

  private boolean crosses(int section, int min, int max) {
    long low = (long) section << shift;
    return low < min || low + width() - 1 > max;
  }
}
