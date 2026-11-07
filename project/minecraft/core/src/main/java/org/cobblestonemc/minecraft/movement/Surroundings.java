/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.ChunkSource;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.MinecraftWorld;

/**
 * What one expansion knows about the blocks around it, past the few its rules read: what makes a
 * step from it worse than slow.
 *
 * <p>Each question is answered for a step's destination, as a strength from 0 (nothing near) to 1
 * (right beside it), falling off linearly with distance. The blocks come from one box around the
 * expanding cell, read once for every step out of it and only as wide as the hazards this expansion
 * can meet need: none at all for a player nothing here can touch.
 *
 * <p>Distances are Chebyshev, in whole blocks. Dangerous terrain and the coast are looked for
 * column by column at the expanding cell's level, which a step up or down a block does not move far
 * from; an approximation, since a penalty only has to be about right.
 */
final class Surroundings {

  /** Nothing to price: every strength is 0. */
  static final Surroundings NONE = new Surroundings(Hazards.NONE, null, null, null);

  /**
   * How many blocks of room a boosted glide needs on every side: with less, it may strike a block.
   */
  static final int BOOST_CLEARANCE = 4;

  /** How far from the shore a boat counts as beside it. */
  static final int COAST_RADIUS = 2;

  private final Hazards hazards;
  private final Cell from;
  private final BlockView box;

  /** Per column of the box, whether it holds dangerous terrain; {@code null} if not looked for. */
  private final boolean[] dangerous;

  private Surroundings(Hazards hazards, Cell from, BlockView box, boolean[] dangerous) {
    this.hazards = hazards;
    this.from = from;
    this.box = box;
    this.dangerous = dangerous;
  }

  /**
   * Reads what the hazards an expansion from {@code from} in {@code state} can meet need.
   *
   * @param chunks where blocks are read from
   * @param world the world
   * @param from the expanding cell
   * @param state the state it expands in
   * @param goal the cell the search is heading for, for read-ahead
   * @param hazards what the player can be priced for
   * @return the surroundings, possibly pending on I/O
   */
  static FutureOr<Surroundings> fetch(
      ChunkSource chunks,
      MinecraftWorld world,
      Cell from,
      TraversalState state,
      Cell goal,
      Hazards hazards) {
    Hazards meets =
        hazards.meetable(
            state.get(MinecraftKeys.VEHICLE), state.get(MinecraftKeys.GLIDING) != null);
    if (meets.isNone()) {
      return FutureOr.of(NONE);
    }
    int radius = 1;
    int dyLow = 0;
    int dyHigh = 3;
    if (meets.dangerRadius() > 0) {
      radius = Math.max(radius, 1 + meets.dangerRadius());
      dyLow = Math.min(dyLow, meets.ledges() ? -(MovementCosts.SAFE_FALL_BLOCKS + 2) : -2);
    }
    if (meets.cramped()) {
      // A neighbor's room, out to one short of the clearance.
      radius = Math.max(radius, BOOST_CLEARANCE);
      dyLow = Math.min(dyLow, -BOOST_CLEARANCE);
      dyHigh = Math.max(dyHigh, BOOST_CLEARANCE);
    }
    if (meets.coast()) {
      radius = Math.max(radius, 1 + COAST_RADIUS);
    }
    return BlockLookup.fetchBox(chunks, world, from, radius, dyLow, dyHigh, goal)
        .map(
            b ->
                new Surroundings(
                    meets,
                    from,
                    b,
                    meets.dangerRadius() > 0
                        ? dangerousColumns(b, from, 1 + meets.dangerRadius(), meets.ledges())
                        : null));
  }

  /**
   * How close {@code to} lies to dangerous terrain: a harmful block from the floor to above the
   * head, or the lip of a drop deep enough to hurt. 0 where there is none within the radius.
   */
  double danger(Cell to) {
    if (dangerous == null) {
      return 0.0;
    }
    int r = hazards.dangerRadius();
    int width = 2 * (r + 1) + 1;
    int nearest = Integer.MAX_VALUE;
    for (int dx = -r; dx <= r; dx++) {
      for (int dz = -r; dz <= r; dz++) {
        int ix = to.x() + dx - from.x() + r + 1;
        int iz = to.z() + dz - from.z() + r + 1;
        if (ix >= 0 && ix < width && iz >= 0 && iz < width && dangerous[ix * width + iz]) {
          nearest = Math.min(nearest, Math.max(Math.abs(dx), Math.abs(dz)));
        }
      }
    }
    return falloff(nearest, r);
  }

  /**
   * How short of room a boosted glide at {@code to} is: 1 right beside a block, falling off to 0 at
   * {@link #BOOST_CLEARANCE} blocks of room. 0 for anyone not boosting a glide.
   */
  double cramped(Cell to) {
    if (!hazards.cramped()) {
      return 0.0;
    }
    int reach = BOOST_CLEARANCE - 1;
    int nearest = Integer.MAX_VALUE;
    for (int dx = -reach; dx <= reach; dx++) {
      for (int dy = -reach; dy <= reach; dy++) {
        for (int dz = -reach; dz <= reach; dz++) {
          if (!box.at(to, dx, dy, dz).isPassable()) {
            nearest =
                Math.min(nearest, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
          }
        }
      }
    }
    return falloff(nearest, reach);
  }

  /** How close a boat at {@code to} is to the shore. 0 for anyone not boating. */
  double coast(Cell to) {
    if (!hazards.coast()) {
      return 0.0;
    }
    int nearest = Integer.MAX_VALUE;
    for (int dx = -COAST_RADIUS; dx <= COAST_RADIUS; dx++) {
      for (int dz = -COAST_RADIUS; dz <= COAST_RADIUS; dz++) {
        MinecraftBlock level = box.at(to, dx, 0, dz);
        boolean land =
            (!level.supportsBoat() && !level.isPassable()) || !box.at(to, dx, 1, dz).isPassable();
        if (land) {
          nearest = Math.min(nearest, Math.max(Math.abs(dx), Math.abs(dz)));
        }
      }
    }
    return falloff(nearest, COAST_RADIUS);
  }

  /** 1 at a distance of 1 or less, falling off linearly to 0 past {@code radius}. */
  private static double falloff(int distance, int radius) {
    if (distance > radius) {
      return 0.0;
    }
    return Math.min(1.0, (double) (radius + 1 - distance) / radius);
  }

  /** Marks each column of the box that holds dangerous terrain near the expanding cell's level. */
  private static boolean[] dangerousColumns(BlockView box, Cell from, int radius, boolean ledges) {
    // A ledge is something to walk off. In the air there is nothing to walk off, however far down
    // the ground is.
    boolean grounded = ledges && box.at(from, 0, -1, 0).isSolidTop();
    int width = 2 * radius + 1;
    boolean[] dangerous = new boolean[width * width];
    for (int ix = 0; ix < width; ix++) {
      for (int iz = 0; iz < width; iz++) {
        Cell column = new Cell(from.x() - radius + ix, from.y(), from.z() - radius + iz);
        dangerous[ix * width + iz] = harmful(box, column) || (grounded && ledge(box, column));
      }
    }
    return dangerous;
  }

  /**
   * A harmful block anywhere from below the floor of a step down to above the head of a step up.
   */
  private static boolean harmful(BlockView box, Cell column) {
    for (int dy = -2; dy <= 2; dy++) {
      if (box.at(column, 0, dy, 0).isDangerous()) {
        return true;
      }
    }
    return false;
  }

  /** Open at body height, with nothing to land on, not even water, before a fall that hurts. */
  private static boolean ledge(BlockView box, Cell column) {
    if (!box.at(column).isPassable() || !box.at(column, 0, 1, 0).isPassable()) {
      return false;
    }
    for (int depth = 1; depth <= MovementCosts.SAFE_FALL_BLOCKS + 1; depth++) {
      MinecraftBlock below = box.at(column, 0, -depth, 0);
      if (!below.isPassable() || below.isWater() || below.isSolidTop()) {
        return false;
      }
    }
    return true;
  }
}
