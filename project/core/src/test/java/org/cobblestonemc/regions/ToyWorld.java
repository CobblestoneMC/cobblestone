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

  /** A toy terrain's classes, with the shape of a game's. */
  enum Toy implements RegionKind {
    /** Air on stone: stood in. */
    GROUND(true, true, false, true),
    /** Air on nothing: fallen through. */
    OPEN(false, false, true, true),
    /** Water: stepped into, and landed in from any height. */
    POOL(false, true, false, true),
    /** Stone, dug through in straight lines. */
    ROCK(false, false, false, false);

    private final boolean standing;
    private final boolean steppable;
    private final boolean open;
    private final boolean convex;

    Toy(boolean standing, boolean steppable, boolean open, boolean convex) {
      this.standing = standing;
      this.steppable = steppable;
      this.open = open;
      this.convex = convex;
    }

    @Override
    public boolean standing() {
      return standing;
    }

    @Override
    public boolean steppable() {
      return steppable;
    }

    @Override
    public boolean open() {
      return open;
    }

    @Override
    public boolean landing(int depth) {
      return (this == GROUND && depth >= 2) || this == POOL;
    }

    @Override
    public boolean convex() {
      return convex;
    }
  }

  /**
   * A toy terrain: ground where air sits on stone, air above it, water, and stone that is dug
   * through.
   */
  static final class Terrain implements RegionTerrain<Blocks, Toy> {

    private final int shift;

    Terrain(int shift) {
      this.shift = shift;
    }

    @Override
    public Class<Toy> classes() {
      return Toy.class;
    }

    @Override
    public int sectionShift() {
      return shift;
    }

    @Override
    public void classify(
        Blocks blocks, int originX, int originY, int originZ, DomainBounds bounds, Toy[] cells) {
      int mask = (1 << shift) - 1;
      for (int cell = 0; cell < cells.length; cell++) {
        int x = originX + (cell & mask);
        int y = originY + (cell >> (2 * shift));
        int z = originZ + ((cell >> shift) & mask);
        if (y < bounds.minY() || y > bounds.maxY()) {
          continue;
        }
        cells[cell] =
            switch (blocks.at(x, y, z)) {
              case STONE -> Toy.ROCK;
              case WATER -> Toy.POOL;
              case AIR ->
                  y > bounds.minY() && blocks.at(x, y - 1, z) == Material.STONE
                      ? Toy.GROUND
                      : Toy.OPEN;
            };
      }
    }

    @Override
    public int deepestFall() {
      return 32;
    }
  }

  static final Terrain TERRAIN = new Terrain(4);

  static RegionSpec<Blocks, Toy> spec() {
    return new RegionSpec<>(TERRAIN, DomainBounds.vertical(MIN_Y, MAX_Y));
  }

  /** Walks ground, swims water, and digs stone. */
  static final class Walker implements RegionAgent<Toy> {
    @Override
    public boolean usable(Toy clazz) {
      return clazz != Toy.OPEN;
    }

    @Override
    public double traverse(
        Toy clazz, double fx, double fy, double fz, double tx, double ty, double tz) {
      double length =
          Math.sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz));
      return switch (clazz) {
        case GROUND -> 0.2 * length;
        case POOL -> 0.3 * length;
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
    public double fall(Toy clazz, int depth) {
      return clazz == Toy.POOL || depth <= 3 ? 0 : Double.POSITIVE_INFINITY;
    }
  }

  /** Hills, a pond and a cave, so a section has mixed cells, falls and several classes. */
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
  static final class Source implements TerrainSource<Blocks> {
    private final Blocks blocks;
    int reads;
    boolean unreadable;

    /** When set, sections arrive only when this completes. */
    CompletableFuture<Void> gate;

    Source(Blocks blocks) {
      this.blocks = blocks;
    }

    Source() {
      this(ToyWorld::hills);
    }

    @Override
    public FutureOr<Blocks> section(int sectionX, int sectionY, int sectionZ) {
      reads++;
      if (unreadable) {
        return FutureOr.of(null);
      }
      if (gate != null) {
        return FutureOr.from(gate.thenApply(ignored -> blocks));
      }
      return FutureOr.of(blocks);
    }
  }
}
