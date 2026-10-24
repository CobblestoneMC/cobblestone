/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import org.cobblestonemc.minecraft.MiningClass;
import org.jetbrains.annotations.Nullable;

/**
 * A kind of terrain, as the region layer sees it. A tag, with no costs of its own: {@link
 * MinecraftAgent} says what each class costs one agent.
 *
 * <p><b>Exactly one class per cell.</b> Every cell belongs to one region of one class. A flyer and
 * a walker read the same regions; they just price them differently, and the walker cannot enter
 * {@link #AIR} at all.
 *
 * <p><b>A kind of terrain earns a class when some agent would pay differently for it.</b> Soul sand
 * and soul soil are both ground, but soul sand drags a walker and soul soil does not, while Soul
 * Speed boots make both fast: two classes. Breakable material is bucketed by {@link MiningClass},
 * because a pickaxe makes stone fast and dirt no faster. Water and its surface are both swum, but
 * only the surface carries a boat. Terrain no agent prices differently from an existing class
 * belongs to that class.
 *
 * <p><b>Adding a class</b> is a line here, saying how a body moves through it ({@link Body}), and
 * its price in {@link MinecraftAgent}, whose exhaustive switch will not compile without one.
 *
 * <p><b>Each class has a fixed id,</b> which is what a region stores. Never reuse one: a retired
 * class's id stays retired, so a column built before it was retired can never read as another.
 */
public enum RegionClass {

  /** A cell a body can stand in: a solid top below, room for a two-block body. */
  STAND(0, Body.GROUND, null),

  /** {@link #STAND}, on soul sand: it drags a walker, but not one in Soul Speed boots. */
  SOUL_SAND(1, Body.GROUND, null),

  /** {@link #STAND}, on soul soil: ordinary ground to a walker, and fast in Soul Speed boots. */
  SOUL_SOIL(2, Body.GROUND, null),

  /** Water with water above it: only swum. */
  WATER(3, Body.WATER, null),

  /** Water open to the air above: swum, or crossed by boat. */
  WATER_SURFACE(4, Body.WATER, null),

  /** Ladders, vines and scaffolding. */
  CLIMB(5, Body.CLIMB, null),

  /** Open space nothing stands in: crossed by flying, or fallen through. */
  AIR(6, Body.OPEN, null),

  /** Breakable by hand: leaves, glass, wool. */
  SOFT(7, Body.SOLID, MiningClass.SOFT),

  /** Soft rock: netherrack and its ores, sandstone. */
  SOFT_ROCK(8, Body.SOLID, MiningClass.SOFT_ROCK),

  /** Rock: stone and its ores. */
  ROCK(9, Body.SOLID, MiningClass.ROCK),

  /**
   * Rock and hard rock mixed: where deepslate is dithered into stone. Never a block's own class,
   * only a region's; see {@link MinecraftTerrain#label}.
   */
  MIXED_ROCK(10, Body.SOLID, null),

  /** Hard rock: deepslate and its ores, end stone. */
  HARD_ROCK(11, Body.SOLID, MiningClass.HARD_ROCK),

  /** Very hard rock: obsidian, whatever else needs a diamond pickaxe, and vaults. */
  VERY_HARD_ROCK(12, Body.SOLID, MiningClass.VERY_HARD_ROCK),

  /** Loose ground: dirt, sand and gravel. */
  LOOSE(13, Body.SOLID, MiningClass.LOOSE),

  /** Anything wooden. */
  WOODEN(14, Body.SOLID, MiningClass.WOODEN);

  /** One more than the highest id: the size of an array indexed by id. */
  public static final int ID_BOUND;

  private static final RegionClass[] BY_ID;

  private static final RegionClass[] BY_MINING = new RegionClass[MiningClass.values().length];

  static {
    int bound = 0;
    for (RegionClass clazz : values()) {
      bound = Math.max(bound, clazz.id + 1);
    }
    ID_BOUND = bound;
    BY_ID = new RegionClass[bound];
    for (RegionClass clazz : values()) {
      if (BY_ID[clazz.id] != null) {
        throw new IllegalStateException("region class id " + clazz.id + " is used twice");
      }
      BY_ID[clazz.id] = clazz;
      if (clazz.mining != null) {
        BY_MINING[clazz.mining.ordinal()] = clazz;
      }
    }
  }

  /**
   * How a body moves through a class: what the region layer joins it by. A fact about the terrain,
   * the same for every agent.
   */
  public enum Body {

    /** Stood in: steps a level up and down, and is landed on from a ledge up to a safe fall. */
    GROUND(true, true, false, true),

    /** Swum: stepped into, and landed in from any height, since water breaks a fall. */
    WATER(false, true, false, true),

    /** Climbed: stepped onto, and caught from a fall up to a safe height. */
    CLIMB(false, true, false, true),

    /** Open space: flown through, or fallen through. */
    OPEN(false, false, true, true),

    /**
     * Breakable material: dug through in straight lines whatever its shape, so its regions need not
     * be convex.
     */
    SOLID(false, false, false, false);

    private final boolean standing;
    private final boolean steppable;
    private final boolean open;
    private final boolean convex;

    Body(boolean standing, boolean steppable, boolean open, boolean convex) {
      this.standing = standing;
      this.steppable = steppable;
      this.open = open;
      this.convex = convex;
    }

    /**
     * Returns whether a body stands in cells of this kind.
     *
     * @return {@code true} for ground
     */
    public boolean standing() {
      return standing;
    }

    /**
     * Returns whether a standing body can step into a cell of this kind a level up or down.
     *
     * @return {@code true} if it can
     */
    public boolean steppable() {
      return steppable;
    }

    /**
     * Returns whether a body falls through cells of this kind.
     *
     * @return {@code true} for open space
     */
    public boolean open() {
      return open;
    }

    /**
     * Returns whether regions of this kind must be close to convex.
     *
     * @return {@code true} if the fill caps their detour
     */
    public boolean convex() {
      return convex;
    }
  }

  private final int id;
  private final Body body;
  private final @Nullable MiningClass mining;

  /**
   * @param id what a region stores the class as; never reused
   * @param body how a body moves through it
   * @param mining the bucket of the blocks it holds, for breakable material that blocks are; {@code
   *     null} otherwise
   */
  RegionClass(int id, Body body, @Nullable MiningClass mining) {
    this.id = id;
    this.body = body;
    this.mining = mining;
  }

  /**
   * Returns the class a region stores as a number.
   *
   * @param id the stored number
   * @return the class, or {@code null} if no class has that number
   */
  public static @Nullable RegionClass byId(int id) {
    return id >= 0 && id < ID_BOUND ? BY_ID[id] : null;
  }

  /**
   * Returns the class of breakable material in a mining bucket.
   *
   * @param mining the bucket
   * @return the class
   */
  public static RegionClass of(MiningClass mining) {
    return BY_MINING[mining.ordinal()];
  }

  /**
   * Returns the number a region stores this class as.
   *
   * @return the id, below {@link #ID_BOUND}
   */
  public int id() {
    return id;
  }

  /**
   * Returns how a body moves through this class.
   *
   * @return the body
   */
  public Body body() {
    return body;
  }

  /**
   * Returns the mining bucket a block of this class is in, or {@code null} for a class no block is,
   * such as {@link #MIXED_ROCK}, or one that is not breakable material.
   *
   * @return the bucket
   */
  public @Nullable MiningClass mining() {
    return mining;
  }

  /**
   * Returns whether this class is breakable material, dug through or, for an agent that cannot dig,
   * a wall at a price.
   *
   * @return {@code true} for the mining buckets and mixed rock
   */
  public boolean solid() {
    return body == Body.SOLID;
  }
}
