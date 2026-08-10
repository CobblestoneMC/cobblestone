/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.BreakChecker;
import net.whimxiqal.odyssey.minecraft.MinecraftAgent;
import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Tunnelling through breakable blocks — mining a cardinal neighbor or the block directly below.
 *
 * <p>Cost is the sum of stone-tool break times of the blocks that must be cleared (feet + head)
 * plus a move. Unbreakable blocks (bedrock, {@code +∞} break time) and blocks the agent may not
 * break yield nothing. Beyond the coarse {@link MinecraftAgent#canBreak} gate, an injected
 * {@link BreakChecker} lets integrations forbid breaking specific blocks (protected regions,
 * man-made block types); that check may be asynchronous, so this mode uses the async
 * {@link #movements} seam. Odyssey never actually breaks blocks — this only routes through blocks the
 * player is expected to mine.
 *
 * @param <A> the agent type
 */
final class MineMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final BreakChecker<A> breakChecker;

  MineMode() {
    this(BreakChecker.allowAll());
  }

  MineMode(BreakChecker<A> breakChecker) {
    this.breakChecker = breakChecker;
  }

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == null;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -2, 2);
  }

  @Override
  protected FutureOr<Collection<Movement<MinecraftStepPayload>>> movements(
      A agent, Cell from, MinecraftWorld world, TraversalState state, BlockView view) {
    // Blocks that a candidate would need to break and that are otherwise breakable — only these need
    // the (possibly async) breakability decision; everything else is settled synchronously below.
    Set<Cell> decisionCells = new LinkedHashSet<>();
    for (int[] dir : HORIZONTAL) {
      Cell dest = from.plus(dir[0], 0, dir[1]);
      collectDecisionCells(view, agent, decisionCells, dest, dest.plus(0, 1, 0));
    }
    collectDecisionCells(view, agent, decisionCells, from.plus(0, -1, 0));

    if (decisionCells.isEmpty()) {
      return FutureOr.of(assemble(agent, from, state, view, Map.of()));
    }
    List<Cell> cells = new ArrayList<>(decisionCells);
    List<FutureOr<Boolean>> checks = new ArrayList<>(cells.size());
    for (Cell cell : cells) {
      checks.add(breakChecker.breakable(agent, cell, world, view.at(cell)));
    }
    return FutureOr.all(checks).map(results -> {
      Map<Cell, Boolean> breakable = new HashMap<>();
      for (int i = 0; i < cells.size(); i++) {
        breakable.put(cells.get(i), results.get(i));
      }
      return assemble(agent, from, state, view, breakable);
    });
  }

  /** Adds the cells among {@code cells} that need a breakability decision (breakable but unresolved). */
  private static <A extends MinecraftAgent> void collectDecisionCells(
      BlockView view, A agent, Set<Cell> out, Cell... cells) {
    for (Cell cell : cells) {
      MinecraftBlock block = view.at(cell);
      if (block.isPassable()) {
        continue;
      }
      if (!Double.isFinite(block.breakTimeSeconds()) || !agent.canBreak(cell)) {
        continue; // already infeasible without asking the checker
      }
      out.add(cell);
    }
  }

  private Collection<Movement<MinecraftStepPayload>> assemble(
      A agent, Cell from, TraversalState state, BlockView view, Map<Cell, Boolean> breakable) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
    for (int[] dir : HORIZONTAL) {
      Cell dest = from.plus(dir[0], 0, dir[1]);
      double breakCost = clearCost(view, agent, breakable, dest, dest.plus(0, 1, 0));
      if (Double.isFinite(breakCost) && view.at(dest, 0, -1, 0).isSolidTop()) {
        moves.add(move(dest, breakCost + MovementCosts.WALK, MinecraftStepType.MINE, state));
      }
    }
    // Dig straight down.
    Cell down = from.plus(0, -1, 0);
    double downCost = clearCost(view, agent, breakable, down);
    if (Double.isFinite(downCost) && view.at(down, 0, -1, 0).isSolidTop()) {
      moves.add(move(down, downCost + MovementCosts.FALL_PER_BLOCK, MinecraftStepType.MINE, state));
    }
    return moves;
  }

  /**
   * Total break time to clear the given cells so a body can occupy them, or {@code +∞} if any
   * non-passable cell there is unbreakable, off-limits, or barred by the break checker.
   */
  private static <A extends MinecraftAgent> double clearCost(
      BlockView view, A agent, Map<Cell, Boolean> breakable, Cell... cells) {
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
      if (!breakable.getOrDefault(cell, Boolean.TRUE)) {
        return Double.POSITIVE_INFINITY; // an integration forbade breaking this block
      }
      total += breakTime;
    }
    return total;
  }
}
