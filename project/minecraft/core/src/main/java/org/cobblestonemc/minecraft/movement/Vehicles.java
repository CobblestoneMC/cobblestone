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
 * Movements tied to a vehicle, where the {@link MinecraftKeys#VEHICLE} traversal state says which
 * one the agent is in.
 */
final class Vehicles {

  private static final int[][] HORIZONTAL = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  private Vehicles() {}

  /**
   * Boarding a boat from foot: entering adjacent water places the boat and sets {@code VEHICLE =
   * BOAT} (a {@code PLACE_BOAT} step).
   */
  static void boardBoat(Cell from, TraversalState state, BlockView view, Edges edges) {
    TraversalState inBoat =
        state
            .with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.BOAT)
            .with(MinecraftKeys.BOAT_CONSUMED, Boolean.TRUE);
    for (int i = 0; i < 4; i++) {
      Cell dest = from.plus(HORIZONTAL[i][0], 0, HORIZONTAL[i][1]);
      if (view.at(dest).supportsBoat() && view.at(dest, 0, 1, 0).isPassable()) {
        edges.add(dest, MovementCosts.PLACE_BOAT, MinecraftStepType.PLACE_BOAT, inBoat);
      }
    }
  }

  /** Travelling fast over water while boating, or stepping out onto adjacent land. */
  static void boat(Cell from, TraversalState state, BlockView view, Edges edges) {
    for (int[] dir : HORIZONTAL) {
      Cell dest = from.plus(dir[0], 0, dir[1]);
      boolean diagonal = dir[0] != 0 && dir[1] != 0;
      if (view.at(dest).supportsBoat()
          && view.at(dest, 0, 1, 0).isPassable()
          && !view.at(dest, 0, 1, 0).isWater()) {
        double cost = MovementCosts.BOAT * (diagonal ? MovementCosts.DIAGONAL : 1.0);
        edges.add(dest, cost, MinecraftStepType.BOAT, state);
      } else if (!diagonal && Geometry.standable(view, dest)) {
        TraversalState onLand =
            state.without(MinecraftKeys.VEHICLE).without(MinecraftKeys.BOAT_CONSUMED);
        edges.add(dest, MovementCosts.BOAT, MinecraftStepType.WALK, onLand);
      }
    }
  }

  /**
   * Fast ground travel while mounted — a state entered via the horse mount transition (Tier-1), so
   * the graph prefers horse routes once the horse is reached. Behaves like a faster walk (flat
   * moves and one-block step-ups), staying mounted.
   */
  static void horse(Cell from, TraversalState state, BlockView view, Edges edges) {
    for (int[] dir : HORIZONTAL) {
      int dx = dir[0];
      int dz = dir[1];
      boolean diagonal = dx != 0 && dz != 0;
      Cell level = from.plus(dx, 0, dz);
      if (Geometry.standable(view, level)) {
        if (diagonal && Geometry.cornerBlocked(view, from, dx, dz)) {
          continue;
        }
        double cost = MovementCosts.HORSE * (diagonal ? MovementCosts.DIAGONAL : 1.0);
        edges.add(level, cost, MinecraftStepType.HORSE, state);
      } else if (!diagonal) {
        Cell up = from.plus(dx, 1, dz);
        if (Geometry.standable(view, up) && view.at(from, 0, 2, 0).isPassable()) {
          edges.add(up, MovementCosts.HORSE, MinecraftStepType.HORSE, state);
        }
      }
    }
  }
}
