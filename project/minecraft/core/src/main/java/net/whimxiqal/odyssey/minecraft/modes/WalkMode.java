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
import net.whimxiqal.odyssey.minecraft.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Walking (and single-block step-up/step-down) on solid ground. Cardinal and horizontal-diagonal
 * moves to a standable cell (no corner-cutting through solids), a jump/step up one block, or a step
 * down one block. Speed is scaled by the footing block's {@code speedFactor} (ice fast, soul sand
 * slow). Larger drops are handled by {@code FallMode}.
 *
 * @param <A> the agent type
 */
final class WalkMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == null;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -2, 2);
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
        double factor = Math.max(view.at(level, 0, -1, 0).speedFactor(), 0.1);
        double cost = MovementCosts.WALK / factor * (diagonal ? MovementCosts.DIAGONAL : 1.0);
        moves.add(move(level, cost, MinecraftStepType.WALK, state));
      } else if (!diagonal) {
        addStepUpOrDown(from, dx, dz, view, state, moves);
      }
    }
    return moves;
  }

  private void addStepUpOrDown(
      Cell from,
      int dx,
      int dz,
      BlockView view,
      TraversalState state,
      List<Movement<MinecraftStepPayload>> moves) {
    Cell up = from.plus(dx, 1, dz);
    if (Geometry.standable(view, up) && view.at(from, 0, 2, 0).isPassable()) {
      boolean half = view.at(from.plus(dx, 0, dz)).isHalfHeight();
      double cost = MovementCosts.WALK + (half ? 0.0 : MovementCosts.JUMP_EXTRA);
      moves.add(move(up, cost, half ? MinecraftStepType.WALK : MinecraftStepType.JUMP, state));
      return;
    }
    Cell down = from.plus(dx, -1, dz);
    if (Geometry.standable(view, down)) {
      moves.add(move(down, MovementCosts.WALK, MinecraftStepType.WALK, state));
    }
  }
}
