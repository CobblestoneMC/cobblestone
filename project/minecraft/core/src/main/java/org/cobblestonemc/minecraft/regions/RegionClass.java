/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

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
 * Speed boots make both fast: two classes. Breakable material is bucketed by its mining class,
 * because a pickaxe makes stone fast and dirt no faster. Water and its surface are both swum, but
 * only the surface carries a boat. Terrain no agent prices differently from an existing class
 * belongs to that class.
 *
 * <p><b>Adding a class</b> is a line here, saying how a body moves through it ({@link Body}), and
 * its price in {@link MinecraftAgent}, whose exhaustive switch will not compile without one; then
 * {@link MinecraftTerrain#VERSION} bumped.
 *
 * <p><b>Each class has a fixed id,</b> which is what a region stores. Never reuse one: a retired
 * class's id stays retired, so a stack built before it was retired can never read as another.
 */
public enum RegionClass {

  /** A cell a body can stand in: a solid top below, room for a two-block body. */
  STAND(0, Body.GROUND),

  /** {@link #STAND}, on soul sand: it drags a walker, but not one in Soul Speed boots. */
  SOUL_SAND(1, Body.GROUND),

  /** {@link #STAND}, on soul soil: ordinary ground to a walker, and fast in Soul Speed boots. */
  SOUL_SOIL(2, Body.GROUND),

  /** Water with water above it: only swum. */
  WATER(3, Body.WATER),

  /** Water open to the air above: swum, or crossed by boat. */
  WATER_SURFACE(4, Body.WATER),

  /** Ladders, vines and scaffolding. */
  CLIMB(5, Body.CLIMB),

  /** Open space nothing stands in: crossed by flying, or fallen through. */
  AIR(6, Body.OPEN),

  /** Breakable by hand: leaves, glass, wool. */
  SOFT(7, Body.SOLID),

  /*
   * The rock classes below fill as one family, so stone dithered into deepslate makes one region
   * rather than shreds of each; each region of that family is then labelled with the class nearest
   * its cells' average hardness, and the label is what an agent prices. See
   * MinecraftTerrain#family and MinecraftTerrain#label.
   */

  /** Soft rock: netherrack and its ores, sandstone. */
  SOFT_ROCK(8, Body.SOLID),

  /** Rock: stone and its ores. */
  ROCK(9, Body.SOLID),

  /**
   * Rock and hard rock mixed: where deepslate is dithered into stone. Only ever a region's label,
   * never a block's own class; see {@link MinecraftTerrain#label}.
   */
  MIXED_ROCK(10, Body.SOLID),

  /** Hard rock: deepslate and its ores, end stone. */
  HARD_ROCK(11, Body.SOLID),

  /**
   * Very hard rock: obsidian, whatever else needs a diamond pickaxe, and vaults.
   *
   * <p>Not one of the rock family, on purpose. Obsidian is over thirty times as hard as stone, so
   * averaged into a stone region a single pocket of it would make the whole region several times
   * dearer to dig; it fills as a family of its own instead. It is also what a tool tier gates,
   * which an agent prices apart.
   */
  VERY_HARD_ROCK(12, Body.SOLID),

  /** Loose ground: dirt, sand and gravel. */
  LOOSE(13, Body.SOLID),

  /** Anything wooden. */
  WOODEN(14, Body.SOLID);

  /** One more than the highest id: the size of an array indexed by id. */
  public static final int ID_BOUND;

  private static final RegionClass[] BY_ID;

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

  /**
   * @param id what a region stores the class as; never reused
   * @param body how a body moves through it
   */
  RegionClass(int id, Body body) {
    this.id = id;
    this.body = body;
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
   * Returns whether this class is breakable material, dug through or, for an agent that cannot dig,
   * a wall at a price.
   *
   * @return {@code true} for the mining buckets and mixed rock
   */
  public boolean solid() {
    return body == Body.SOLID;
  }
}
