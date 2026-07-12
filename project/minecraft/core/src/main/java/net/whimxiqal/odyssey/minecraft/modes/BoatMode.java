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
import net.whimxiqal.odyssey.minecraft.api.MinecraftAgent;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Boat travel — the vehicle mode that demonstrates {@code TraversalState} transitions.
 *
 * <p>On foot (with a boat, ensured at mode-list assembly) it can enter adjacent water, placing the
 * boat and setting {@code VEHICLE = BOAT} (a {@code PLACE_BOAT} step). While boating it travels fast
 * over water and can step out onto adjacent land, clearing the vehicle state.
 *
 * @param <A> the agent type
 */
final class BoatMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {
      {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  BoatMode() {
    super(MinecraftStepType.BOAT);
  }

  @Override
  protected boolean applies(A agent, TraversalState state) {
    MinecraftKeys.Vehicle vehicle = state.get(MinecraftKeys.VEHICLE);
    return vehicle == null || vehicle == MinecraftKeys.Vehicle.BOAT;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -1, 2);
  }

  @Override
  protected Collection<Movement<MinecraftStepType, MinecraftInstruction>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepType, MinecraftInstruction>> moves = new ArrayList<>();
    boolean boating = state.get(MinecraftKeys.VEHICLE) == MinecraftKeys.Vehicle.BOAT;
    for (int[] dir : HORIZONTAL) {
      Cell dest = from.plus(dir[0], 0, dir[1]);
      boolean diagonal = dir[0] != 0 && dir[1] != 0;
      if (boating) {
        if (view.at(dest).supportsBoat() && view.at(dest, 0, 1, 0).isPassable()) {
          double cost = MovementCosts.BOAT * (diagonal ? MovementCosts.DIAGONAL : 1.0);
          moves.add(move(dest, cost, MinecraftStepType.BOAT, state));
        } else if (!diagonal && Geometry.standable(view, dest)) {
          TraversalState onLand = state.without(MinecraftKeys.VEHICLE).without(MinecraftKeys.BOAT_CONSUMED);
          moves.add(move(dest, MovementCosts.BOAT, MinecraftStepType.WALK, onLand));
        }
      } else if (!diagonal && view.at(dest).supportsBoat() && view.at(dest, 0, 1, 0).isPassable()) {
        TraversalState inBoat = state
            .with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.BOAT)
            .with(MinecraftKeys.BOAT_CONSUMED, Boolean.TRUE);
        moves.add(move(dest, MovementCosts.PLACE_BOAT, MinecraftStepType.PLACE_BOAT, inBoat));
      }
    }
    return moves;
  }
}
