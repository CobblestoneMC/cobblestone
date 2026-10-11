/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import org.cobblestonemc.Cell;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftAgent;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.jetbrains.annotations.Nullable;

/**
 * The local movements of an agent out of any vehicle: one pass over the neighbors of the expanding
 * cell, deciding for each how — if at all — the agent gets there.
 *
 * <ul>
 *   <li><b>Walk</b> onto a standable neighbor (no corner-cutting through solids), <b>jump</b> up a
 *       block (a half-height step is still a walk), or step down one. Speed is scaled by the
 *       footing block's {@code speedFactor} (ice fast, soul sand slow). Larger drops are {@link
 *       Falls}.
 *   <li><b>Swim</b> into any face-adjacent or horizontally-diagonal water.
 *   <li><b>Climb</b> ladders, vines and scaffolding: up and down while on one, grabbing an adjacent
 *       one, and stepping sideways off scaffolding.
 *   <li>Step through a <b>doorway</b> — a door or fence gate — to the far side. Passability is a
 *       material-level fact, so a door reads impassable whichever way it stands and nothing else
 *       crosses it. A shut door is crossable if it opens by hand (wooden) or is iron with a
 *       pressure plate the player is standing on; buttons, levers and distant redstone are out of
 *       scope. Either way the step lands <em>beyond</em> the doorway, so the player never ends up
 *       standing in a door about to shut. Trapdoors are left out: open, one stands as a wall across
 *       the face it hangs on rather than opening a way through.
 *   <li><b>Mine</b> into a cardinal neighbor the agent cannot simply walk to, up a step it cannot
 *       simply jump, or down through the floor. See {@link Mining}.
 *   <li><b>Fly</b> to any of the 26 neighbors a body fits in, with no footing requirement.
 * </ul>
 */
final class OnFoot {

  /** Cardinals first, then diagonals. */
  private static final int[][] HORIZONTAL = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  private OnFoot() {}

  static <A extends MinecraftAgent> void offer(
      Abilities can,
      @Nullable Mining<A> mining,
      A agent,
      MinecraftWorld world,
      Cell from,
      TraversalState state,
      BlockView view,
      Edges edges) {
    MinecraftBlock here = view.at(from);
    boolean onClimbable = can.climb() && here.isClimbable();

    Cell up = from.plus(0, 1, 0);
    Cell down = from.plus(0, -1, 0);
    if (onClimbable) {
      if (view.at(up).isClimbable() || view.at(up).isPassable()) {
        edges.add(up, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state);
      }
      if (view.at(down).isClimbable() || Geometry.bodyFits(view, down)) {
        edges.add(down, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state);
      }
    }
    if (can.swim()) {
      if (view.at(up).isWater()) {
        edges.add(up, MovementCosts.SWIM, MinecraftStepType.SWIM, state);
      }
      if (view.at(down).isWater()) {
        edges.add(down, MovementCosts.SWIM, MinecraftStepType.SWIM, state);
      }
    }
    if (mining != null) {
      mining.offer(agent, world, view, state, edges, down, MovementCosts.FALL_PER_BLOCK, down);
    }

    for (int[] dir : HORIZONTAL) {
      int dx = dir[0];
      int dz = dir[1];
      boolean diagonal = dx != 0 && dz != 0;
      Cell level = from.plus(dx, 0, dz);
      boolean standable = Geometry.standable(view, level);

      if (can.swim() && view.at(level).isWater()) {
        double cost = MovementCosts.SWIM * (diagonal ? MovementCosts.DIAGONAL : 1.0);
        edges.add(level, cost, MinecraftStepType.SWIM, state);
      }
      if (diagonal) {
        if (can.walk() && standable && !Geometry.cornerBlocked(view, from, dx, dz)) {
          double factor = view.at(level, 0, -1, 0).speedFactor();
          edges.add(
              level,
              MovementCosts.WALK / factor * MovementCosts.DIAGONAL,
              MinecraftStepType.WALK,
              state);
        }
        continue;
      }

      Cell above = from.plus(dx, 1, dz);
      boolean stepUp =
          !standable && Geometry.standable(view, above) && view.at(up, 0, 1, 0).isPassable();
      if (can.walk()) {
        if (standable) {
          double factor = view.at(level, 0, -1, 0).speedFactor();
          edges.add(level, MovementCosts.WALK / factor, MinecraftStepType.WALK, state);
        } else if (stepUp) {
          MinecraftBlock floor = view.at(level);
          MinecraftStepType type =
              floor.isHalfHeight() ? MinecraftStepType.WALK : MinecraftStepType.JUMP;
          edges.add(
              above,
              MovementCosts.WALK / floor.speedFactor() * MovementCosts.DIAGONAL,
              type,
              state);
        } else {
          Cell below = from.plus(dx, -1, dz);
          if (Geometry.standable(view, below)) {
            double factor = view.at(below, 0, -1, 0).speedFactor();
            edges.add(
                below,
                MovementCosts.WALK / factor * MovementCosts.DIAGONAL,
                MinecraftStepType.WALK,
                state);
          }
        }
      }
      if (can.climb()) {
        if (view.at(level).isClimbable()) {
          edges.add(level, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state);
        } else if (onClimbable && here.isScaffolding() && standable) {
          edges.add(level, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state);
        }
      }
      if (can.doors()) {
        doorway(can, from, dx, dz, here.isPressurePlate(), state, view, edges);
      }
      if (mining != null) {
        if (!standable) {
          mining.offer(
              agent,
              world,
              view,
              state,
              edges,
              level,
              MovementCosts.WALK,
              level,
              level.plus(0, 1, 0));
        }
        if (!stepUp) {
          mining.offer(
              agent,
              world,
              view,
              state,
              edges,
              above,
              MovementCosts.WALK * MovementCosts.DIAGONAL,
              up.plus(0, 1, 0),
              above,
              above.plus(0, 1, 0));
        }
      }
    }

    if (can.flightHeight() > 0) {
      fly(can.flightHeight(), from, state, view, edges);
    }
  }

  /** Stepping through the doorway one block out in direction {@code (dx, dz)}, if there is one. */
  private static void doorway(
      Abilities can,
      Cell from,
      int dx,
      int dz,
      boolean standingOnPlate,
      TraversalState state,
      BlockView view,
      Edges edges) {
    Cell doorway = from.plus(dx, 0, dz);
    MinecraftBlock door = view.at(doorway);
    if (!door.isDoor() || door.isTrapdoor()) {
      return;
    }
    Cell beyond = from.plus(dx * 2, 0, dz * 2);
    if (!view.at(doorway, 0, -1, 0).isSolidTop() || !Geometry.standable(view, beyond)) {
      return; // no floor under the doorway, or nowhere to land on the far side
    }
    // Two blocks of ground are covered — through the doorway and out the other side — so the step
    // is priced as two walked blocks, not one.
    double walk = 2 * MovementCosts.WALK;
    if (door.isOpen()) {
      edges.add(beyond, walk, MinecraftStepType.WALK, state);
    } else if (can.openDoors() && (door.opensByHand() || standingOnPlate)) {
      edges.add(beyond, walk + MovementCosts.OPEN_DOOR, MinecraftStepType.OPEN_DOOR, state);
    }
  }

  /**
   * Free 3D flight (creative / allow-flight, or elytra gliding) to any of the 26 neighbors where a
   * body fits; cost scales with euclidean distance. {@code height} is the clearance a body needs: 2
   * for a walking-height flier, 1 for an elytra glider modelled thin enough to slip through a
   * 1-block hole (an end gateway).
   */
  private static void fly(
      int height, Cell from, TraversalState state, BlockView view, Edges edges) {
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dy == 0 && dz == 0) {
            continue;
          }
          Cell target = from.plus(dx, dy, dz);
          if (!Geometry.bodyFits(view, target, height)) {
            continue;
          }
          if (dx != 0 && dz != 0 && Geometry.diagonalBlocked(view, from, dx, dy, dz)) {
            continue;
          }
          double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
          edges.add(target, MovementCosts.FLY * distance, MinecraftStepType.FLY, state);
        }
      }
    }
  }
}
