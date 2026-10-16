/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
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
  public static final int VERSION = 4;

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
    return RegionClass.ALL.length;
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
    for (int cell = 0; cell < volume; cell++) {
      int y = columnMinY + (cell >> 8);
      blocks[cell] =
          y >= worldMinY && y <= worldMaxY ? chunk.block(cell & 15, y, (cell >> 4) & 15) : null;
    }
    for (int cell = 0; cell < volume; cell++) {
      MinecraftBlock block = blocks[cell];
      classes[cell] = -1;
      if (block == null) {
        continue;
      }
      if (block.isWater()) {
        // The surface is where a boat floats: open to the air, as BoatMode wants it.
        MinecraftBlock above = cell + 256 < volume ? blocks[cell + 256] : null;
        boolean open = above == null || (above.isPassable() && !above.isWater());
        classes[cell] = (byte) (open ? RegionClass.WATER_SURFACE : RegionClass.WATER).ordinal();
      } else if (block.isClimbable() || block.isScaffolding()) {
        classes[cell] = (byte) RegionClass.CLIMB.ordinal();
      } else if (block.isPassable()) {
        MinecraftBlock floor = cell >= 256 ? blocks[cell - 256] : null;
        MinecraftBlock head = cell + 256 < volume ? blocks[cell + 256] : null;
        boolean stands = floor != null && floor.isSolidTop() && (head == null || head.isPassable());
        classes[cell] = (byte) (stands ? ground(floor) : RegionClass.AIR).ordinal();
      } else if (!block.isLava() && !Double.isInfinite(block.breakTimeSeconds())) {
        classes[cell] = (byte) RegionClass.solid(block.miningClass()).ordinal();
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

  @Override
  public boolean standing(int klass) {
    return RegionClass.ALL[klass].standing();
  }

  @Override
  public boolean steppable(int klass) {
    RegionClass c = RegionClass.ALL[klass];
    return c.standing() || c.water() || c == RegionClass.CLIMB;
  }

  @Override
  public boolean open(int klass) {
    return klass == RegionClass.AIR.ordinal();
  }

  @Override
  public boolean landing(int klass, int depth) {
    RegionClass c = RegionClass.ALL[klass];
    // One block down is a step, already joined by the step contacts.
    return (c.standing() && depth >= 2 && depth <= MAX_DROP)
        || c.water()
        || (c == RegionClass.CLIMB && depth <= MAX_DROP);
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
  public int family(int klass) {
    return switch (RegionClass.ALL[klass]) {
      case SOFT_ROCK, ROCK, MIXED_ROCK, HARD_ROCK -> RegionClass.ROCK.ordinal();
      default -> klass;
    };
  }

  /**
   * A rock region's class is the band of rock nearest its cells' average hardness: soft rock, rock,
   * mixed rock or hard rock. Nothing but the class is stored, so an estimate across a region is at
   * most half a band from its true average.
   */
  @Override
  public int label(int family, int[] counts) {
    if (family != RegionClass.ROCK.ordinal()) {
      return family;
    }
    double sum = 0;
    int cells = 0;
    for (RegionClass band : ROCK_BANDS) {
      sum += counts[band.ordinal()] * hardness(band);
      cells += counts[band.ordinal()];
    }
    double mean = sum / cells;
    RegionClass nearest = ROCK_BANDS[0];
    for (RegionClass band : ROCK_BANDS) {
      if (Math.abs(hardness(band) - mean) < Math.abs(hardness(nearest) - mean)) {
        nearest = band;
      }
    }
    return nearest.ordinal();
  }

  /** The classes a rock region can be, softest first. */
  private static final RegionClass[] ROCK_BANDS = {
    RegionClass.SOFT_ROCK, RegionClass.ROCK, RegionClass.MIXED_ROCK, RegionClass.HARD_ROCK
  };

  /**
   * Returns the hardness typical of a class of breakable material, as Minecraft defines hardness.
   *
   * <p>A fact about the terrain, not a cost: it is what a rock region's class is chosen by, and
   * what an agent prices breaking it from with whatever tool it has.
   *
   * @param klass a class for which {@link RegionClass#solid()} holds
   * @return the hardness
   */
  public static double hardness(RegionClass klass) {
    return switch (klass) {
      case SOFT -> 0.2;
      case SOFT_ROCK -> 0.4;
      case ROCK -> 1.5;
      case MIXED_ROCK -> 2.25;
      case HARD_ROCK -> 3.0;
      case OBSIDIAN -> 50.0;
      case DIRT -> 0.5;
      case WOOD -> 2.0;
      default -> throw new IllegalArgumentException(klass + " is not breakable material");
    };
  }

  @Override
  public boolean convex(int klass) {
    // Rock is dug through in straight lines whatever its shape, so its regions need not be convex.
    return !RegionClass.ALL[klass].solid();
  }
}
