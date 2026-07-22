/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.MinecraftAgent;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Fast ground travel while mounted. Only active when {@code VEHICLE = HORSE} — a state entered via
 * the horse mount transition (Tier-1), so the graph prefers horse routes once the horse is reached.
 * Behaves like a faster walk (flat moves and one-block step-ups), staying mounted.
 *
 * @param <A> the agent type
 */
final class HorseMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {
      {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == MinecraftKeys.Vehicle.HORSE;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -1, 2);
  }

  @Override
  protected Collection<Movement<MinecraftStepPayload>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
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
        moves.add(move(level, cost, MinecraftStepType.HORSE, state));
      } else if (!diagonal) {
        Cell up = from.plus(dx, 1, dz);
        if (Geometry.standable(view, up) && view.at(from, 0, 2, 0).isPassable()) {
          moves.add(move(up, MovementCosts.HORSE + MovementCosts.JUMP_EXTRA, MinecraftStepType.HORSE, state));
        }
      }
    }
    return moves;
  }
}
