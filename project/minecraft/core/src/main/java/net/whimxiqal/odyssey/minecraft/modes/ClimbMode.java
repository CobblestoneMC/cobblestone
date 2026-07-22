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
 * Climbing ladders, vines, and scaffolding: up and down while on a climbable block, grabbing an
 * adjacent climbable from standing, and (for scaffolding) stepping sideways.
 *
 * @param <A> the agent type
 */
final class ClimbMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == null;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -1, 2);
  }

  @Override
  protected Collection<Movement<MinecraftStepPayload>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
    boolean onClimbable = view.at(from).isClimbable();
    if (onClimbable) {
      Cell up = from.plus(0, 1, 0);
      if (view.at(up).isClimbable() || view.at(up).isPassable()) {
        moves.add(move(up, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state));
      }
      Cell down = from.plus(0, -1, 0);
      if (view.at(down).isClimbable() || Geometry.bodyFits(view, down)) {
        moves.add(move(down, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state));
      }
    }
    for (int[] dir : HORIZONTAL) {
      Cell side = from.plus(dir[0], 0, dir[1]);
      if (view.at(side).isClimbable()) {
        moves.add(move(side, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state));
      } else if (onClimbable && view.at(from).isScaffolding() && Geometry.standable(view, side)) {
        moves.add(move(side, MovementCosts.CLIMB, MinecraftStepType.CLIMB, state));
      }
    }
    return moves;
  }
}
