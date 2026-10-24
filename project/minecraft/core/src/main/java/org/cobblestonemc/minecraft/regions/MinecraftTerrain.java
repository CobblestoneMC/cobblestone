/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.MinecraftWorld.Environment;
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
 * water or a ladder, and drops off a ledge onto ground up to {@link #MAX_DROP} below, onto a ladder
 * as far, and into water from any height up to {@link #MAX_WATER_DROP}.
 *
 * <p><b>Change any of it, bump {@link #VERSION}.</b> Stored columns built under other rules are
 * built again.
 */
public final class MinecraftTerrain implements RegionTerrain<MinecraftChunk> {

  /** The one instance: it holds no state but scratch per thread. */
  public static final MinecraftTerrain INSTANCE = new MinecraftTerrain();

  /** The version of these rules; see {@link RegionTerrain#version()}. */
  public static final int VERSION = 5;

  /** A chunk is 16 blocks along X and Z: {@code 1 << 4}. */
  private static final int CHUNK_SHIFT = 4;

  /**
   * The deepest drop onto ground a fall portal joins.
   *
   * <p>{@code MovementCosts.SAFE_FALL_BLOCKS}. FallMode will go further at the price of damage,
   * which it charges at several seconds a block; a route that needs that is rare, and pricing it as
   * a cheap drop would invite the fine search to every cliff edge.
   */
  static final int MAX_DROP = 3;

  /** The deepest drop into water a fall portal joins: water breaks any fall. */
  static final int MAX_WATER_DROP = 32;

  /** A column's blocks, per thread: builds run in parallel. */
  private final ThreadLocal<MinecraftBlock[]> scratch = new ThreadLocal<>();

  private MinecraftTerrain() {}

  @Override
  public int version() {
    return VERSION;
  }

  @Override
  public int classes() {
    return RegionClass.ID_BOUND;
  }

  @Override
  public int columnShift() {
    return CHUNK_SHIFT;
  }

  /**
   * Returns how a world's terrain is cut into regions.
   *
   * @param world the world
   * @return its spec
   */
  public static RegionSpec<MinecraftChunk> spec(MinecraftWorld world) {
    return new RegionSpec<>(INSTANCE, world.minY(), world.maxY());
  }

  @Override
  public void classify(
      MinecraftChunk chunk, int worldMinY, int worldMaxY, int columnMinY, byte[] classes) {
    int volume = classes.length;
    MinecraftBlock[] blocks = scratch.get();
    if (blocks == null || blocks.length != volume) {
      blocks = new MinecraftBlock[volume];
      scratch.set(blocks);
    }
    int layer = 1 << (2 * CHUNK_SHIFT);
    int mask = (1 << CHUNK_SHIFT) - 1;
    for (int cell = 0; cell < volume; cell++) {
      int y = columnMinY + (cell >> (2 * CHUNK_SHIFT));
      blocks[cell] =
          y >= worldMinY && y <= worldMaxY
              ? chunk.block(cell & mask, y, (cell >> CHUNK_SHIFT) & mask)
              : null;
    }
    for (int cell = 0; cell < volume; cell++) {
      MinecraftBlock block = blocks[cell];
      classes[cell] = -1;
      if (block == null) {
        continue;
      }
      if (block.isWater()) {
        // The surface is where a boat floats: open to the air, as BoatMode wants it.
        MinecraftBlock above = cell + layer < volume ? blocks[cell + layer] : null;
        boolean open = above == null || (above.isPassable() && !above.isWater());
        classes[cell] = (byte) (open ? RegionClass.WATER_SURFACE : RegionClass.WATER).id();
      } else if (block.isClimbable() || block.isScaffolding()) {
        classes[cell] = (byte) RegionClass.CLIMB.id();
      } else if (block.isPassable()) {
        MinecraftBlock floor = cell >= layer ? blocks[cell - layer] : null;
        MinecraftBlock head = cell + layer < volume ? blocks[cell + layer] : null;
        boolean stands = floor != null && floor.isSolidTop() && (head == null || head.isPassable());
        classes[cell] = (byte) (stands ? ground(floor) : RegionClass.AIR).id();
      } else if (!block.isLava() && !Double.isInfinite(block.breakTimeSeconds())) {
        classes[cell] = (byte) RegionClass.solid(block.miningClass()).id();
      }
      // Lava, and unbreakable blocks such as bedrock: nothing, not a region a miner could be priced
      // through.
    }
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

  /** The class stored as a number; the builder only asks about numbers it was given. */
  private static RegionClass of(int clazz) {
    return RegionClass.byId(clazz);
  }

  @Override
  public boolean standing(int clazz) {
    return of(clazz).body().standing();
  }

  @Override
  public boolean steppable(int clazz) {
    return of(clazz).body().steppable();
  }

  @Override
  public boolean open(int clazz) {
    return of(clazz).body().open();
  }

  @Override
  public boolean landing(int clazz, int depth) {
    // One block down onto ground is a step, already joined by the step contacts.
    return switch (of(clazz).body()) {
      case GROUND -> depth >= 2 && depth <= MAX_DROP;
      case WATER -> true;
      case CLIMB -> depth <= MAX_DROP;
      case OPEN, SOLID -> false;
    };
  }

  @Override
  public int deepestFall() {
    return MAX_WATER_DROP;
  }

  /**
   * The family a class fills with: rock of every hardness for a pickaxe fills as one, because
   * deepslate is dithered into stone block by block and a region per kind would shred both. Every
   * other class is a family of its own.
   */
  @Override
  public int family(int clazz) {
    return switch (of(clazz)) {
      case SOFT_ROCK, ROCK, MIXED_ROCK, HARD_ROCK -> RegionClass.ROCK.id();
      default -> clazz;
    };
  }

  /**
   * A rock region's class is the band of rock nearest its cells' average hardness: soft rock, rock,
   * mixed rock or hard rock. Nothing but the class is stored, so an estimate across a region is at
   * most half a band from its true average.
   */
  @Override
  public int label(int family, int[] counts) {
    if (family != RegionClass.ROCK.id()) {
      return family;
    }
    double sum = 0;
    int cells = 0;
    for (RegionClass band : ROCK_BANDS) {
      sum += counts[band.id()] * hardness(band);
      cells += counts[band.id()];
    }
    double mean = sum / cells;
    RegionClass nearest = ROCK_BANDS[0];
    for (RegionClass band : ROCK_BANDS) {
      if (Math.abs(hardness(band) - mean) < Math.abs(hardness(nearest) - mean)) {
        nearest = band;
      }
    }
    return nearest.id();
  }

  /** Netherrack's hardness: soft rock in the Nether. */
  private static final double NETHERRACK_HARDNESS = 0.4;

  /** Sandstone's hardness: soft rock outside the Nether. */
  private static final double SANDSTONE_HARDNESS = 0.8;

  /** The classes a rock region can be, softest first. */
  private static final RegionClass[] ROCK_BANDS = {
    RegionClass.SOFT_ROCK, RegionClass.ROCK, RegionClass.MIXED_ROCK, RegionClass.HARD_ROCK
  };

  /**
   * Returns the hardness typical of a class of breakable material, as Minecraft defines hardness.
   *
   * <p>A fact about the terrain, not a cost: it is what a rock region's class is chosen by, the
   * same in every world. An agent prices breaking a class by {@link #hardness(RegionClass,
   * Environment)} instead, which knows what the class is made of where it is.
   *
   * @param clazz a class for which {@link RegionClass#solid()} holds
   * @return the hardness
   */
  public static double hardness(RegionClass clazz) {
    return switch (clazz) {
      case SOFT -> 0.2;
      case SOFT_ROCK -> NETHERRACK_HARDNESS;
      case ROCK -> 1.5;
      case MIXED_ROCK -> 2.25;
      case HARD_ROCK -> 3.0;
      case VERY_HARD_ROCK -> 50.0;
      case LOOSE -> 0.5;
      case WOODEN -> 2.0;
      default -> throw new IllegalArgumentException(clazz + " is not breakable material");
    };
  }

  /**
   * Returns the hardness typical of a class of breakable material in a world of an environment.
   *
   * <p>Soft rock is netherrack in the Nether and mostly sandstone anywhere else, at twice the
   * hardness. Every other class is the same everywhere.
   *
   * @param clazz a class for which {@link RegionClass#solid()} holds
   * @param environment the world's environment
   * @return the hardness
   */
  public static double hardness(RegionClass clazz, Environment environment) {
    if (clazz == RegionClass.SOFT_ROCK && environment != Environment.NETHER) {
      return SANDSTONE_HARDNESS;
    }
    return hardness(clazz);
  }

  @Override
  public boolean convex(int clazz) {
    return of(clazz).body().convex();
  }
}
