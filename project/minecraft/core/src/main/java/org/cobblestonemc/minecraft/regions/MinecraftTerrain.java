/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import java.util.EnumMap;
import java.util.Map;
import org.cobblestonemc.DomainBounds;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.MinecraftWorld.Environment;
import org.cobblestonemc.minecraft.MiningClass;
import org.cobblestonemc.regions.RegionSpec;
import org.cobblestonemc.regions.RegionTerrain;

/**
 * Minecraft's terrain for the region layer: which {@link RegionClass} each block is, and how a
 * player moves between them.
 *
 * <p><b>Classes come from a block, the block under it and the block over it.</b> Open space with a
 * solid top below and headroom above is ground, of a class by what it stands on; without them it is
 * air. Water open to the air is a surface. Breakable material is bucketed by its {@link
 * org.cobblestonemc.minecraft.MiningClass}. Lava and unbreakable blocks belong to no region.
 *
 * <p><b>The joining rules are a walking body's.</b> Ground steps a level up or down into ground,
 * water or a ladder, and drops off a ledge onto ground, a ladder or water up to {@link
 * #DEEPEST_FALL} below. Which of those falls a player survives is {@link MinecraftAgent}'s to say.
 *
 * <p><b>Change any of it, bump {@link #VERSION}.</b> Stored sections built under other rules are
 * built again.
 *
 * <p><b>One per environment,</b> because what soft rock is depends on the dimension: netherrack in
 * the Nether, mostly sandstone elsewhere, at twice the hardness. A rock region's class is chosen by
 * its cells' hardness, so the same blocks can make a different class in a different dimension.
 */
public final class MinecraftTerrain implements RegionTerrain<MinecraftChunk, RegionClass> {

  private static final Map<Environment, MinecraftTerrain> BY_ENVIRONMENT =
      new EnumMap<>(Environment.class);

  static {
    for (Environment environment : Environment.values()) {
      BY_ENVIRONMENT.put(environment, new MinecraftTerrain(environment));
    }
  }

  /** The version of these rules; see {@link RegionTerrain#version()}. */
  public static final int VERSION = 8;

  /** A chunk section is 16 blocks a side: {@code 1 << 4}. */
  private static final int SECTION_SHIFT = 4;

  private static final int WIDTH = 1 << SECTION_SHIFT;

  private static final int MASK = WIDTH - 1;

  /** Blocks in one layer of a section. */
  private static final int LAYER = WIDTH * WIDTH;

  /** The deepest fall a portal joins: two sections, far past anything a player survives on land. */
  static final int DEEPEST_FALL = 32;

  /** The environment of the worlds this terrain classifies, which says what its soft rock is. */
  private final Environment environment;

  /**
   * A section's blocks and the layers over and under it, which its classes read, per thread: builds
   * run in parallel.
   */
  private final ThreadLocal<MinecraftBlock[]> scratch = new ThreadLocal<>();

  private MinecraftTerrain(Environment environment) {
    this.environment = environment;
  }

  /**
   * Returns the terrain of worlds of an environment.
   *
   * @param environment the environment
   * @return its terrain
   */
  public static MinecraftTerrain of(Environment environment) {
    return BY_ENVIRONMENT.get(environment);
  }

  @Override
  public int version() {
    return VERSION;
  }

  @Override
  public Class<RegionClass> classes() {
    return RegionClass.class;
  }

  @Override
  public int sectionShift() {
    return SECTION_SHIFT;
  }

  /**
   * Returns how a world's terrain is cut into regions.
   *
   * @param world the world
   * @return its spec
   */
  public static RegionSpec<MinecraftChunk, RegionClass> spec(MinecraftWorld world) {
    return new RegionSpec<>(of(world.environment()), world.bounds());
  }

  /**
   * Classifies a section of a chunk, reading the layer under it and the layer over it as well: a
   * cell stands on the block below it and needs headroom in the block above.
   */
  @Override
  public void classify(
      MinecraftChunk chunk,
      int originX,
      int originY,
      int originZ,
      DomainBounds bounds,
      RegionClass[] cells) {
    MinecraftBlock[] blocks = scratch.get();
    if (blocks == null) {
      blocks = new MinecraftBlock[LAYER * (WIDTH + 2)];
      scratch.set(blocks);
    }
    // Layer 0 is the one under the section, and layer WIDTH + 1 the one over it.
    for (int layer = 0; layer < WIDTH + 2; layer++) {
      int y = originY + layer - 1;
      boolean inside = y >= bounds.minY() && y <= bounds.maxY();
      for (int i = 0; i < LAYER; i++) {
        blocks[layer * LAYER + i] = inside ? chunk.block(i & MASK, y, i >> SECTION_SHIFT) : null;
      }
    }
    for (int cell = 0; cell < cells.length; cell++) {
      int at = cell + LAYER;
      MinecraftBlock block = blocks[at];
      if (block == null) {
        continue;
      }
      if (block.isWater()) {
        // The surface is where a boat floats: open to the air, as BoatMode wants it.
        MinecraftBlock above = blocks[at + LAYER];
        boolean open = above == null || (above.isPassable() && !above.isWater());
        cells[cell] = open ? RegionClass.WATER_SURFACE : RegionClass.WATER;
      } else if (block.isClimbable() || block.isScaffolding()) {
        cells[cell] = RegionClass.CLIMB;
      } else if (block.isPassable()) {
        MinecraftBlock floor = blocks[at - LAYER];
        MinecraftBlock head = blocks[at + LAYER];
        boolean stands = floor != null && floor.isSolidTop() && (head == null || head.isPassable());
        cells[cell] = stands ? ground(floor) : RegionClass.AIR;
      } else if (!block.isLava() && !Double.isInfinite(block.breakTimeSeconds())) {
        cells[cell] = solid(block.miningClass());
      }
      // Lava, and unbreakable blocks such as bedrock: nothing, not a region a miner could be priced
      // through.
    }
  }

  /**
   * Returns whether one block replacing another leaves every class as it was.
   *
   * <p>A cell's class is read from its own block and the blocks above and below it, by the
   * properties compared here, so a change none of them sees changes no region: a crop growing, a
   * fluid's level moving, grass turning to dirt. A listener that skips those saves the section a
   * rebuild. Reads what {@link #classify} reads; change one, change both.
   *
   * @param before the block that was there
   * @param after the block that is there now
   * @return {@code true} if the regions need not be rebuilt for it
   */
  public static boolean classifiesAlike(MinecraftBlock before, MinecraftBlock after) {
    return before.isWater() == after.isWater()
        && before.isLava() == after.isLava()
        && before.isClimbable() == after.isClimbable()
        && before.isScaffolding() == after.isScaffolding()
        && before.isPassable() == after.isPassable()
        && before.isSolidTop() == after.isSolidTop()
        && before.boostsSoulSpeed() == after.boostsSoulSpeed()
        && (before.speedFactor() < 1) == (after.speedFactor() < 1)
        && Double.isInfinite(before.breakTimeSeconds())
            == Double.isInfinite(after.breakTimeSeconds())
        && before.miningClass() == after.miningClass();
  }

  /** The class of breakable material in a mining bucket: one each, and no block is mixed rock. */
  static RegionClass solid(MiningClass mining) {
    return switch (mining) {
      case SOFT -> RegionClass.SOFT;
      case SOFT_ROCK -> RegionClass.SOFT_ROCK;
      case ROCK -> RegionClass.ROCK;
      case HARD_ROCK -> RegionClass.HARD_ROCK;
      case VERY_HARD_ROCK -> RegionClass.VERY_HARD_ROCK;
      case LOOSE -> RegionClass.LOOSE;
      case WOODEN -> RegionClass.WOODEN;
    };
  }

  /**
   * The class of a cell a body stands in, by what it stands on.
   *
   * <p>Soul Speed works on soul sand and soul soil alike, but only soul sand drags a walker without
   * it, which its speed factor says.
   */
  private static RegionClass ground(MinecraftBlock floor) {
    if (!floor.boostsSoulSpeed()) {
      return RegionClass.STAND;
    }
    return floor.speedFactor() < 1 ? RegionClass.SOUL_SAND : RegionClass.SOUL_SOIL;
  }

  @Override
  public int deepestFall() {
    return DEEPEST_FALL;
  }

  /**
   * The family a class fills with: rock of every hardness for a pickaxe fills as one, because
   * deepslate is dithered into stone block by block and a region per kind would shred both. Every
   * other class is a family of its own.
   */
  @Override
  public RegionClass family(RegionClass clazz) {
    return switch (clazz) {
      case SOFT_ROCK, ROCK, MIXED_ROCK, HARD_ROCK -> RegionClass.ROCK;
      default -> clazz;
    };
  }

  /**
   * A rock region's class is the band of rock nearest its cells' average hardness: soft rock, rock,
   * mixed rock or hard rock, each as hard as it is in this terrain's environment. Nothing but the
   * class is stored, so an estimate across a region is at most half a band from its true average.
   */
  @Override
  public RegionClass label(RegionClass family, int[] counts) {
    if (family != RegionClass.ROCK) {
      return family;
    }
    double sum = 0;
    int cells = 0;
    for (RegionClass band : ROCK_BANDS) {
      sum += counts[band.ordinal()] * hardness(band, environment);
      cells += counts[band.ordinal()];
    }
    double mean = sum / cells;
    RegionClass nearest = ROCK_BANDS[0];
    for (RegionClass band : ROCK_BANDS) {
      if (Math.abs(hardness(band, environment) - mean)
          < Math.abs(hardness(nearest, environment) - mean)) {
        nearest = band;
      }
    }
    return nearest;
  }

  /** Netherrack's hardness: soft rock in the Nether. */
  private static final double NETHERRACK_HARDNESS = 0.4;

  /** Sandstone's hardness: soft rock outside the Nether. */
  private static final double SANDSTONE_HARDNESS = 0.8;

  /**
   * The classes a rock region can be labelled, softest first. Very hard rock is not one: it fills
   * on its own; see {@link RegionClass#VERY_HARD_ROCK}.
   */
  private static final RegionClass[] ROCK_BANDS = {
    RegionClass.SOFT_ROCK, RegionClass.ROCK, RegionClass.MIXED_ROCK, RegionClass.HARD_ROCK
  };

  /**
   * Returns the hardness typical of a class of breakable material in a world of an environment, as
   * Minecraft defines hardness.
   *
   * <p>A fact about the terrain, not a cost: it is what a rock region's class is chosen by, and
   * what an agent prices breaking it by. Soft rock is netherrack in the Nether and mostly sandstone
   * anywhere else, at twice the hardness. Every other class is the same everywhere.
   *
   * @param clazz a class for which {@link RegionClass#solid()} holds
   * @param environment the world's environment
   * @return the hardness
   */
  public static double hardness(RegionClass clazz, Environment environment) {
    return switch (clazz) {
      case SOFT -> 0.2;
      case SOFT_ROCK ->
          environment == Environment.NETHER ? NETHERRACK_HARDNESS : SANDSTONE_HARDNESS;
      case ROCK -> 1.5;
      case MIXED_ROCK -> 2.25;
      case HARD_ROCK -> 3.0;
      case VERY_HARD_ROCK -> 50.0;
      case LOOSE -> 0.5;
      case WOODEN -> 2.0;
      default -> throw new IllegalArgumentException(clazz + " is not breakable material");
    };
  }
}
