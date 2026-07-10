/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

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
 * Free 3D flight (creative / allow-flight). Moves to any of the 26 neighbors where a body fits, with
 * no footing requirement; cost scales with euclidean distance. Included in the mode list only when
 * the agent can fly, so this class need not re-check that.
 *
 * @param <A> the agent type
 */
final class FlyMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  FlyMode() {
    super(MinecraftStepType.FLY);
  }

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == null;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -1, 2);
  }

  @Override
  protected Collection<Movement<MinecraftStepType, MinecraftInstruction>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepType, MinecraftInstruction>> moves = new ArrayList<>();
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dy == 0 && dz == 0) {
            continue;
          }
          Cell target = from.plus(dx, dy, dz);
          if (!Geometry.bodyFits(view, target)) {
            continue;
          }
          if (dy == 0 && dx != 0 && dz != 0 && Geometry.cornerBlocked(view, from, dx, dz)) {
            continue;
          }
          double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
          moves.add(move(target, MovementCosts.FLY * distance, MinecraftStepType.FLY, state));
        }
      }
    }
    return moves;
  }
}
