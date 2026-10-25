/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.DomainBounds;
import org.cobblestonemc.FutureOr;

/** A toy domain for testing the region layer without any game: air, stone and water. */
final class ToyWorld {

  static final int MIN_Y = 0;
  static final int MAX_Y = 47;

  private ToyWorld() {}

  /** What a toy domain is made of, cell by cell. */
  enum Material {
    AIR,
    STONE,
    WATER
  }

  /** A toy domain's material at each cell, by X, Y and Z. */
  interface Blocks {
    Material at(int x, int y, int z);
  }

  /** One stack of a toy domain: its material by local X and Z and Y. */
  interface Stack {
    Material at(int x, int y, int z);
  }

  /**
   * A toy terrain with the shape of a game's: ground where air sits on stone, air above it, water,
   * and stone that is dug through.
   */
  static final class Terrain implements RegionTerrain<Stack> {
    static final int GROUND = 0;
    static final int OPEN = 1;
    static final int POOL = 2;
    static final int ROCK = 3;

    private final int version;
    private final int shift;

    Terrain(int version, int shift) {
      this.version = version;
      this.shift = shift;
    }

    @Override
    public int version() {
      return version;
    }

    @Override
    public int classes() {
      return 4;
    }

    @Override
    public int sectionShift() {
      return shift;
    }

    @Override
    public void classify(
        Stack stack, int domainMinY, int domainMaxY, int stackMinY, byte[] classes) {
      int mask = (1 << shift) - 1;
      for (int cell = 0; cell < classes.length; cell++) {
        int y = stackMinY + (cell >> (2 * shift));
        int x = cell & mask;
        int z = (cell >> shift) & mask;
        if (y < domainMinY || y > domainMaxY) {
          classes[cell] = -1;
          continue;
        }
        classes[cell] =
            (byte)
                switch (stack.at(x, y, z)) {
                  case STONE -> ROCK;
                  case WATER -> POOL;
                  case AIR ->
                      y > domainMinY && stack.at(x, y - 1, z) == Material.STONE ? GROUND : OPEN;
                };
      }
    }

    @Override
    public boolean standing(int clazz) {
      return clazz == GROUND;
    }

    @Override
    public boolean steppable(int clazz) {
      return clazz == GROUND || clazz == POOL;
    }

    @Override
    public boolean open(int clazz) {
      return clazz == OPEN;
    }

    @Override
    public boolean landing(int clazz, int depth) {
      return (clazz == GROUND && depth >= 2) || clazz == POOL;
    }

    @Override
    public int deepestFall() {
      return 32;
    }

    @Override
    public boolean convex(int clazz) {
      return clazz != ROCK;
    }
  }

  static final Terrain TERRAIN = new Terrain(1, 4);

  static RegionSpec<Stack> spec() {
    return new RegionSpec<>(TERRAIN, DomainBounds.vertical(MIN_Y, MAX_Y));
  }

  /** Walks ground, swims water, and digs stone. */
  static final class Walker implements RegionAgent {
    @Override
    public boolean usable(int clazz) {
      return clazz != Terrain.OPEN;
    }

    @Override
    public double traverse(
        int clazz, double fx, double fy, double fz, double tx, double ty, double tz) {
      double length =
          Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
      return switch (clazz) {
        case Terrain.GROUND -> 0.2 * length;
        case Terrain.POOL -> 0.3 * length;
        default -> 2.8 * Math.max(1, length);
      };
    }

    @Override
    public double cheapest() {
      return 0.2;
    }

    @Override
    public double fallback() {
      return 2.8;
    }

    @Override
    public double fall(int clazz, int depth) {
      return clazz == Terrain.POOL || depth <= 3 ? 0 : Double.POSITIVE_INFINITY;
    }
  }

  /** Hills, a pond and a cave, so a stack has mixed sections, falls and several classes. */
  static Material hills(int x, int y, int z) {
    int ground = 10 + Math.floorMod(x / 5 + z / 7, 4);
    if (y > ground) {
      return y <= 11 && Math.floorMod(x, 13) < 4 && Math.floorMod(z, 11) < 4
          ? Material.WATER
          : Material.AIR;
    }
    if (y > 3 && y < 6 && Math.floorMod(x + z, 9) < 3) {
      return Material.AIR; // a cave
    }
    return Material.STONE;
  }

  /** A terrain source over a toy domain, which counts what it is asked for. */
  static final class Source implements TerrainSource<Stack> {
    private final Blocks blocks;
    private final int width;
    int reads;
    boolean unreadable;

    /** When set, stacks arrive only when this completes. */
    CompletableFuture<Void> gate;

    Source(Blocks blocks, int width) {
      this.blocks = blocks;
      this.width = width;
    }

    Source() {
      this(ToyWorld::hills, 16);
    }

    @Override
    public FutureOr<Stack> stack(int stackX, int stackZ) {
      reads++;
      if (unreadable) {
        return FutureOr.of(null);
      }
      Stack stack = (x, y, z) -> blocks.at(stackX * width + x, y, stackZ * width + z);
      if (gate != null) {
        return FutureOr.from(gate.thenApply(ignored -> stack));
      }
      return FutureOr.of(stack);
    }
  }
}
