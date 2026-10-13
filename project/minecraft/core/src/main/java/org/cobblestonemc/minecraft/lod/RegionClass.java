/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

/**
 * What a cell of terrain is, as the region layer sees it.
 *
 * <p><b>Exactly one class per cell.</b> {@link Medium} lets a cell be several things at once --
 * water is swimmable and boatable, open air is flyable and, a block above ground, walkable -- and
 * the slice-coverage profile paid for that by never knowing which of them a crossing actually used.
 * Here a cell belongs to one region of one class, and what an agent can do with each class is
 * decided later, by {@link RegionCost}. A flyer and a walker read the same regions; they just price
 * them differently and the walker cannot enter {@link #AIR} at all.
 *
 * <p>The order is stored: a region keeps its class as {@link #ordinal()}.
 */
public enum RegionClass {

  /** A cell a body can stand in: a solid top below, room for a two-block body. */
  STAND,

  /** {@link #STAND}, on soul sand or soul soil, which drags unless the agent has Soul Speed. */
  SOUL,

  /** Water: swum through, or boated across where it has a surface. */
  WATER,

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
   * @return {@code true} for {@link #STAND} and {@link #SOUL}
   */
  public boolean standing() {
    return this == STAND || this == SOUL;
  }
}
