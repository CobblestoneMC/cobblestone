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
import java.util.concurrent.CompletableFuture;
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
 * plus a move. Blocks that are synchronously off-limits — unbreakable (bedrock, {@code +∞} break
 * time) or barred by the coarse {@link MinecraftAgent#canBreak} gate — yield nothing.
 *
 * <p>An optional {@link BreakChecker} lets integrations forbid breaking specific blocks
 * asynchronously (protected regions, man-made block types). The mode does not wait on it: it emits
 * the mining edge <b>optimistically</b> and attaches the check as the movement's {@link
 * Movement#restricted() restricted} future (the logical OR of the feet/head block checks), so the
 * search drops just that edge if the block turns out barred. With no checker (the common case) no
 * future is attached at all. Odyssey never actually breaks blocks — this only routes through blocks
 * the player is expected to mine.
 *
 * @param <A> the agent type
 */
final class MineMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private static final int[][] ALL = {
    {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}
  };

  private final BreakChecker<A> breakChecker; // nullable: null = no integration constrains mining

  MineMode() {
    this(null);
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
    return Neighborhood.box(from, 1, -2, 3);
  }

  @Override
  protected FutureOr<Collection<Movement<MinecraftStepPayload>>> movements(
      A agent, Cell from, MinecraftWorld world, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
    for (int[] dir : HORIZONTAL) {
      Cell dest = from.plus(dir[0], 0, dir[1]);
      Movement<MinecraftStepPayload> move =
          mineMove(agent, world, state, view, dest, MovementCosts.WALK, dest, dest.plus(0, 1, 0));
      if (move != null) {
        moves.add(move);
      }
    }
    // mine up a step
    for (int[] dir : HORIZONTAL) {
      Cell dest = from.plus(dir[0], 1, dir[1]);
      Movement<MinecraftStepPayload> move =
          mineMove(
              agent,
              world,
              state,
              view,
              dest,
              MovementCosts.WALK * MovementCosts.DIAGONAL,
              from.plus(0, 2, 0),
              dest,
              dest.plus(0, 1, 0));
      if (move != null) {
        moves.add(move);
      }
    }
    Cell down = from.plus(0, -1, 0);
    Movement<MinecraftStepPayload> move =
        mineMove(agent, world, state, view, down, MovementCosts.FALL_PER_BLOCK, down);
    if (move != null) {
      moves.add(move);
    }
    return FutureOr.of(moves);
  }

  /**
   * Builds the mining move onto {@code dest} that clears {@code breakCells}, or {@code null} if any
   * required block is synchronously off-limits or the destination has no footing. The (optional)
   * async breakability check is attached to the movement rather than awaited.
   */
  private Movement<MinecraftStepPayload> mineMove(
      A agent,
      MinecraftWorld world,
      TraversalState state,
      BlockView view,
      Cell dest,
      double moveCost,
      Cell... breakCells) {
    if (!view.at(dest, 0, -1, 0).isSolidTop()) {
      return null; // nothing to stand on after mining
    }
    double breakTime = 0.0;
    List<CompletableFuture<Boolean>> checks = null;
    for (Cell cell : breakCells) {
      MinecraftBlock block = view.at(cell);
      if (block.isPassable()) {
        continue;
      }

      boolean uncoversLava = false;
      for (int[] dir : ALL) {
        MinecraftBlock maybeLava = view.at(cell.plus(dir[0], dir[1], dir[2]));
        if (maybeLava.isLava()) {
          uncoversLava = true;
          break;
        }
      }
      if (uncoversLava) {
        return null;
      }

      double time = block.breakTimeSeconds();
      if (!Double.isFinite(time) || !agent.canBreak(cell)) {
        return null; // unbreakable or off-limits — settled synchronously
      }
      breakTime += time;
      if (breakChecker != null) {
        if (checks == null) {
          checks = new ArrayList<>(breakCells.length);
        }
        checks.add(breakChecker.breakable(agent, cell, world, block));
      }
    }
    return move(dest, breakTime + moveCost, MinecraftStepType.MINE, state, restricted(checks));
  }

  /**
   * Combines the per-block breakability checks into a single "is this edge restricted" future:
   * {@code true} if any block may not be broken. {@code null} when there is nothing to check.
   */
  private static CompletableFuture<Boolean> restricted(List<CompletableFuture<Boolean>> breakable) {
    if (breakable == null || breakable.isEmpty()) {
      return null;
    }
    if (breakable.size() == 1) {
      return breakable.get(0).thenApply(allowed -> !allowed);
    }
    return CompletableFuture.allOf(breakable.toArray(new CompletableFuture<?>[0]))
        .thenApply(
            ignored -> {
              for (CompletableFuture<Boolean> check : breakable) {
                if (!Boolean.TRUE.equals(check.getNow(Boolean.TRUE))) {
                  return true; // a block may not be broken → the edge is restricted
                }
              }
              return false;
            });
  }
}
