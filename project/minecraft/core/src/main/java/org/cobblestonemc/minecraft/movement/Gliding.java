/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import org.cobblestonemc.Cell;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * Gliding on an elytra, once in the air: the steps of a player whose state is {@link
 * MinecraftKeys#GLIDING}, and how a player on the ground starts gliding by stepping off a drop.
 * Taking off from the ground with a rocket is {@link Takeoff}.
 *
 * <p>A glider's body is one block tall, so it slips through a one-block hole such as an end
 * gateway. Every step goes to one of the 26 neighbors and is one of:
 *
 * <ul>
 *   <li><b>Boosted</b>, with a rocket held: any direction, {@link MovementCosts#BOOST} a block,
 *       plus a penalty where there is too little room to steer (see {@link Edges#addBoosted}).
 *   <li><b>Gliding down</b>, with or without one: a block lower, {@link MovementCosts#GLIDE_DOWN} a
 *       block.
 *   <li><b>Coasting</b> level without a boost: {@link MovementCosts#GLIDE_LEVEL} a block. Slow, but
 *       it threads a tight space, where a boost would strike the walls.
 * </ul>
 *
 * <p>A glider never climbs without a boost. A step that ends standing on the ground, or down into
 * water, ends the glide.
 */
final class Gliding {

  private Gliding() {}

  /** The steps of a player gliding at {@code from}. */
  static void offer(Abilities can, Cell from, TraversalState state, BlockView view, Edges edges) {
    TraversalState landed = state.without(MinecraftKeys.GLIDING);
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dy == 0 && dz == 0) {
            continue;
          }
          Cell to = from.plus(dx, dy, dz);
          // Water is not passable, but a glider may come down into it: a splashdown.
          boolean splash = dy <= 0 && view.at(to).isWater();
          if (!(splash || Geometry.bodyFits(view, to, 1)) || cutsACorner(view, from, dx, dy, dz)) {
            continue;
          }
          double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
          TraversalState after = splash || Geometry.standable(view, to) ? landed : state;
          if (can.rockets() > 0) {
            edges.addBoosted(to, distance, after);
          }
          if (dy < 0) {
            edges.add(to, MovementCosts.GLIDE_DOWN * distance, MinecraftStepType.ELYTRA, after);
          } else if (dy == 0) {
            edges.add(to, MovementCosts.GLIDE_LEVEL * distance, MinecraftStepType.ELYTRA, after);
          }
        }
      }
    }
  }

  /**
   * A player on the ground at {@code from} opening the elytra by stepping off a drop: out to a
   * neighbor on the same level with at least two blocks of air beneath it.
   */
  static void deploy(Cell from, TraversalState state, BlockView view, Edges edges) {
    TraversalState gliding = state.with(MinecraftKeys.GLIDING, true);
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        Cell to = from.plus(dx, 0, dz);
        if (!Geometry.bodyFits(view, to)
            || !view.at(to, 0, -1, 0).isPassable()
            || !view.at(to, 0, -2, 0).isPassable()
            || view.at(to, 0, -1, 0).isWater()
            || (dx != 0 && dz != 0 && Geometry.cornerBlocked(view, from, dx, dz))) {
          continue;
        }
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        edges.add(to, MovementCosts.WALK * distance, MinecraftStepType.ELYTRA, gliding);
      }
    }
  }

  /** Whether a glider can move into {@code cell}, or splash down into it when going down. */
  private static boolean open(BlockView view, Cell cell, int dy) {
    return Geometry.bodyFits(view, cell, 1) || (dy < 0 && view.at(cell).isWater());
  }

  /**
   * Whether a diagonal step squeezes past a solid block: a one-block-tall body still cannot pass
   * between two blocks that meet at a corner.
   */
  private static boolean cutsACorner(BlockView view, Cell from, int dx, int dy, int dz) {
    int axes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
    if (axes < 2) {
      return false;
    }
    return (dx != 0 && !Geometry.bodyFits(view, from.plus(dx, 0, 0), 1))
        || (dy != 0 && !open(view, from.plus(0, dy, 0), dy))
        || (dz != 0 && !Geometry.bodyFits(view, from.plus(0, 0, dz), 1));
  }
}
