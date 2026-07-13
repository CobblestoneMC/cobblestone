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
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.api.*;

/**
 * Swimming through water — moves to any face-adjacent or horizontally-diagonal water cell. Exiting
 * water onto land is handled by {@code WalkMode} (which offers moves to standable neighbors).
 *
 * @param <A> the agent type
 */
final class SwimMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] DIRECTIONS = {
      {0, 1, 0}, {0, -1, 0},
      {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
      {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}
  };

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == null;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -1, 1);
  }

  @Override
  protected Collection<Movement<MinecraftStepPayload>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
    for (int[] dir : DIRECTIONS) {
      Cell dest = from.plus(dir[0], dir[1], dir[2]);
      if (!view.at(dest).isWater()) {
        continue;
      }
      boolean diagonal = dir[0] != 0 && dir[2] != 0;
      double cost = MovementCosts.SWIM * (diagonal ? MovementCosts.DIAGONAL : 1.0);
      moves.add(move(dest, cost, MinecraftStepType.SWIM, state));
    }
    return moves;
  }
}
