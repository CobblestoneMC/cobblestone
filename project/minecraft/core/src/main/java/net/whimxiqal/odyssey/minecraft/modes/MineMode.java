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
import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Tunnelling through breakable blocks — mining a cardinal neighbor or the block directly below.
 *
 * <p>Cost is the sum of stone-tool break times of the blocks that must be cleared (feet + head)
 * plus a move. Unbreakable blocks (bedrock, {@code +∞} break time) and blocks the agent may not
 * break yield nothing. Odyssey never actually breaks blocks — this only routes through blocks the
 * player is expected to mine.
 *
 * @param <A> the agent type
 */
final class MineMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

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
      Cell dest = from.plus(dir[0], 0, dir[1]);
      double breakCost = clearCost(view, agent, dest, dest.plus(0, 1, 0));
      if (Double.isFinite(breakCost) && view.at(dest, 0, -1, 0).isSolidTop()) {
        moves.add(move(dest, breakCost + MovementCosts.WALK, MinecraftStepType.MINE, state));
      }
    }
    // Dig straight down.
    Cell down = from.plus(0, -1, 0);
    double downCost = clearCost(view, agent, down);
    if (Double.isFinite(downCost) && view.at(down, 0, -1, 0).isSolidTop()) {
      moves.add(move(down, downCost + MovementCosts.FALL_PER_BLOCK, MinecraftStepType.MINE, state));
    }
    return moves;
  }

  /**
   * Total break time to clear the given cells so a body can occupy them, or {@code +∞} if any
   * non-passable cell there is unbreakable or off-limits.
   */
  private double clearCost(BlockView view, A agent, Cell... cells) {
    double total = 0.0;
    for (Cell cell : cells) {
      MinecraftBlock block = view.at(cell);
      if (block.isPassable()) {
        continue;
      }
      double breakTime = block.breakTimeSeconds();
      if (!Double.isFinite(breakTime) || !agent.canBreak(cell)) {
        return Double.POSITIVE_INFINITY;
      }
      total += breakTime;
    }
    return total;
  }
}
