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
import org.cobblestonemc.minecraft.UnknownBlock;

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
  static final Surroundings NONE = new Surroundings(Hazards.NONE, null, null, null, false);

  /** How far from a wall or ceiling a glider counts as cramped. */
  static final int CRAMPED_RADIUS = 2;

  /** How far from the shore a boat counts as beside it. */
  static final int COAST_RADIUS = 2;

  /** How far above a glider cover is looked for: trees, roofs, a cave's ceiling. */
  static final int COVER_HEIGHT = 24;

  private final Hazards hazards;
  private final Cell from;
  private final BlockView box;

  /** Per column of the box, whether it holds dangerous terrain; {@code null} if not looked for. */
  private final boolean[] dangerous;

  /** Whether anything solid stands over the expanding cell, for a glider. */
  private final boolean covered;

  private Surroundings(
      Hazards hazards, Cell from, BlockView box, boolean[] dangerous, boolean covered) {
    this.hazards = hazards;
    this.from = from;
    this.box = box;
    this.dangerous = dangerous;
    this.covered = covered;
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
    Hazards meets = hazards.meetable(state.get(MinecraftKeys.VEHICLE));
    if (meets.isNone()) {
      return FutureOr.of(NONE);
    }
    int radius = 1;
    int dyLow = 0;
    if (meets.dangerRadius() > 0) {
      radius = Math.max(radius, 1 + meets.dangerRadius());
      dyLow = Math.min(dyLow, meets.ledges() ? -(MovementCosts.SAFE_FALL_BLOCKS + 2) : -2);
    }
    if (meets.cramped()) {
      radius = Math.max(radius, 1 + CRAMPED_RADIUS);
      dyLow = Math.min(dyLow, -(1 + CRAMPED_RADIUS));
    }
    if (meets.coast()) {
      radius = Math.max(radius, 1 + COAST_RADIUS);
    }
    FutureOr<BlockView> box = BlockLookup.fetchBox(chunks, world, from, radius, dyLow, 3, goal);
    FutureOr<BlockView> above =
        meets.cramped()
            ? BlockLookup.fetchBox(chunks, world, from.plus(0, 4, 0), 0, 0, COVER_HEIGHT, goal)
            : FutureOr.of(BlockView.empty());
    return box.flatMap(
        b ->
            above.map(
                a ->
                    new Surroundings(
                        meets,
                        from,
                        b,
                        meets.dangerRadius() > 0
                            ? dangerousColumns(b, from, 1 + meets.dangerRadius(), meets.ledges())
                            : null,
                        meets.cramped() && covers(a, from.plus(0, 4, 0)))));
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
   * How cramped a glider is at {@code to}: 1 under cover, else how close the nearest block it could
   * strike is. 0 for anyone but a glider.
   */
  double cramped(Cell to) {
    if (!hazards.cramped()) {
      return 0.0;
    }
    if (covered) {
      return 1.0;
    }
    int nearest = Integer.MAX_VALUE;
    for (int dx = -CRAMPED_RADIUS; dx <= CRAMPED_RADIUS; dx++) {
      for (int dy = -CRAMPED_RADIUS; dy <= CRAMPED_RADIUS; dy++) {
        for (int dz = -CRAMPED_RADIUS; dz <= CRAMPED_RADIUS; dz++) {
          if (!box.at(to, dx, dy, dz).isPassable()) {
            nearest =
                Math.min(nearest, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
          }
        }
      }
    }
    return falloff(nearest, CRAMPED_RADIUS);
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

  /** Whether a player at {@code to} has its head underwater; {@code false} if not looked for. */
  boolean headUnderwater(Cell to) {
    return hazards.airBlocks() > 0 && box.at(to, 0, 1, 0).isWater();
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

  /** Whether any block in the column above stops a glider climbing straight out; not the sky. */
  private static boolean covers(BlockView above, Cell bottom) {
    for (int dy = 0; dy <= COVER_HEIGHT; dy++) {
      MinecraftBlock block = above.at(bottom, 0, dy, 0);
      if (block != UnknownBlock.INSTANCE && !block.isPassable()) {
        return true;
      }
    }
    return false;
  }
}
