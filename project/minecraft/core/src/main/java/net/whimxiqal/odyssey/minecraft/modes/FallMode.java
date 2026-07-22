/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.MinecraftAgent;
import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Falling: stepping off a cardinal edge (or straight down) and dropping to the first supported cell
 * or water below. Handles drops of two or more blocks (a one-block step-down is a {@code WalkMode}
 * move). Damage beyond the safe distance is costed as a deterrent (a multiple of heal time); a fall
 * into water takes no damage; a fall onto a hazard is not offered.
 *
 * @param <A> the agent type
 */
final class FallMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int MAX_FALL_SCAN = 16;

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == null;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    Set<Cell> cells = new HashSet<>();
    for (int[] dir : HORIZONTAL) {
      Cell column = from.plus(dir[0], 0, dir[1]);
      cells.add(column.plus(0, 1, 0));
      for (int d = 0; d <= MAX_FALL_SCAN + 1; d++) {
        cells.add(column.plus(0, -d, 0));
      }
    }
    return cells;
  }

  @Override
  protected Collection<Movement<MinecraftStepPayload>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
    for (int[] dir : HORIZONTAL) {
      boolean straightDown = dir[0] == 0 && dir[1] == 0;
      Cell entry = from.plus(dir[0], 0, dir[1]);
      if (!Geometry.bodyFits(view, entry)) {
        continue; // wall in the way
      }
      if (straightDown && view.at(from, 0, -1, 0).isSolidTop()) {
        continue; // standing on solid ground, not falling straight down
      }
      if (!straightDown && Geometry.standable(view, entry)) {
        continue; // flat ground — WalkMode handles it
      }
      double stepOff = straightDown ? 0.0 : MovementCosts.WALK;
      addFall(from, entry, view, state, stepOff, moves);
    }
    return moves;
  }

  private void addFall(
      Cell from, Cell entry, BlockView view, TraversalState state, double stepOff,
      List<Movement<MinecraftStepPayload>> moves) {
    for (int d = 0; d <= MAX_FALL_SCAN; d++) {
      Cell here = entry.plus(0, -d, 0);
      MinecraftBlock block = view.at(here);
      if (!Geometry.bodyFits(view, here) && !block.isWater()) {
        return; // hit a ceiling before landing
      }
      int distance = from.y() - here.y();
      if (block.isWater()) {
        addLanding(here, distance, stepOff, false, view, state, moves);
        return;
      }
      MinecraftBlock below = view.at(here, 0, -1, 0);
      if (below.isSolidTop()) {
        addLanding(here, distance, stepOff, true, view, state, moves);
        return;
      }
    }
  }

  private void addLanding(
      Cell landing, int distance, double stepOff, boolean onSolid, BlockView view,
      TraversalState state, List<Movement<MinecraftStepPayload>> moves) {
    if (distance < 2) {
      return; // one-block drops are WalkMode's job
    }
    if (view.at(landing).isDangerous()) {
      return; // don't fall onto lava/fire
    }
    double cost = stepOff + MovementCosts.FALL_PER_BLOCK * distance;
    if (onSolid) {
      double damageHalfHearts = Math.max(0, distance - MovementCosts.SAFE_FALL_BLOCKS);
      cost += MovementCosts.DAMAGE_COST_MULTIPLIER
          * MovementCosts.HEAL_SECONDS_PER_HALF_HEART * damageHalfHearts;
    }
    moves.add(move(landing, cost, MinecraftStepType.FALL, state));
  }
}
