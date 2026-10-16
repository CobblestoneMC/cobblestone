/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

/**
 * A kind of terrain, as the region layer sees it. A tag, with no costs of its own: {@link
 * RegionCost} says what each class costs one agent.
 *
 * <p><b>Exactly one class per cell.</b> Every cell belongs to one region of one class. A flyer and
 * a walker read the same regions; they just price them differently, and the walker cannot enter
 * {@link #AIR} at all.
 *
 * <p><b>A kind of terrain earns a class when some agent would pay differently for it.</b> Soul sand
 * and soul soil are both ground, but soul sand drags a walker and soul soil does not, while Soul
 * Speed boots make both fast: two classes. Water and its surface are both swum, but only the
 * surface carries a boat. Terrain no agent prices differently from an existing class belongs to
 * that class.
 *
 * <p>The order is stored: a region keeps its class as {@link #ordinal()}. Adding or reordering a
 * class needs {@link RegionColumnBuilder#CLASSIFIER_VERSION} bumped.
 */
public enum RegionClass {

  /** A cell a body can stand in: a solid top below, room for a two-block body. */
  STAND,

  /** {@link #STAND}, on soul sand: it drags a walker, but not one in Soul Speed boots. */
  SOUL_SAND,

  /** {@link #STAND}, on soul soil: ordinary ground to a walker, and fast in Soul Speed boots. */
  SOUL_SOIL,

  /** Water with water above it: only swum. */
  WATER,

  /** Water open to the air above: swum, or crossed by boat. */
  WATER_SURFACE,

  /** Ladders, vines and scaffolding. */
  CLIMB,

  /** Open space nothing stands in: crossed by flying, or fallen through. */
  AIR,

  /** Breakable material: dug through, or for an agent that cannot dig, a wall at a price. */
  SOLID;

  /** Every class, cached; {@code values()} allocates. */
  public static final RegionClass[] ALL = values();

  /**
   * Returns whether a walking body stands in cells of this class, so moves between them step up and
   * down as well as across.
   *
   * @return {@code true} for the classes of ground
   */
  public boolean standing() {
    return this == STAND || this == SOUL_SAND || this == SOUL_SOIL;
  }

  /**
   * Returns whether this class is water, surface or not.
   *
   * @return {@code true} for {@link #WATER} and {@link #WATER_SURFACE}
   */
  public boolean water() {
    return this == WATER || this == WATER_SURFACE;
  }
}
