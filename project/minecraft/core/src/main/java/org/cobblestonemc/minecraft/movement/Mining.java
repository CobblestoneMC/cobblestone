/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.BreakChecker;
import org.cobblestonemc.minecraft.MinecraftAgent;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.jetbrains.annotations.Nullable;

/**
 * Tunnelling through breakable blocks.
 *
 * <p>Cost is the sum of stone-tool break times of the blocks that must be cleared plus a move.
 * Blocks that are synchronously off-limits — unbreakable (bedrock, {@code +∞} break time), barred
 * by the coarse {@link MinecraftAgent#canBreak} gate, or holding back lava — yield nothing. A move
 * that would break nothing is not mining at all, and is left to the rules for walking.
 *
 * <p>An optional {@link BreakChecker} lets integrations forbid breaking specific blocks
 * asynchronously (protected regions, man-made block types). Mining does not wait on it: the edge is
 * emitted <b>optimistically</b> with the check attached as its {@link Movement#restricted()
 * restricted} future (the logical OR of the per-block checks), so the search drops just that edge
 * if a block turns out barred. With no checker (the common case) no future is attached at all.
 * Cobblestone never actually breaks blocks — this only routes through blocks the player is expected
 * to mine.
 *
 * @param <A> the agent type
 */
final class Mining<A extends MinecraftAgent> {

  private static final int[][] FACES = {
    {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}
  };

  private final @Nullable BreakChecker<A> breakChecker; // null = no integration constrains mining

  Mining(@Nullable BreakChecker<A> breakChecker) {
    this.breakChecker = breakChecker;
  }

  /**
   * Offers the mining move onto {@code dest} that clears {@code breakCells}, unless the destination
   * has no footing, a required block is synchronously off-limits, or there is nothing to break.
   */
  void offer(
      A agent,
      MinecraftWorld world,
      BlockView view,
      TraversalState state,
      Edges edges,
      Cell dest,
      double moveCost,
      Cell... breakCells) {
    if (!view.at(dest, 0, -1, 0).isSolidTop()) {
      return; // nothing to stand on after mining
    }
    double breakTime = 0.0;
    int broken = 0;
    List<CheckTarget> targets = null;
    for (Cell cell : breakCells) {
      MinecraftBlock block = view.at(cell);
      if (block.isPassable()) {
        continue;
      }
      if (uncoversLava(view, cell)) {
        return;
      }
      double time = block.breakTimeSeconds();
      if (!Double.isFinite(time) || !agent.canBreak(cell)) {
        return; // unbreakable or off-limits — settled synchronously
      }
      breakTime += time;
      broken++;
      if (breakChecker != null) {
        if (targets == null) {
          targets = new ArrayList<>(breakCells.length);
        }
        targets.add(new CheckTarget(cell, block));
      }
    }
    if (broken == 0) {
      return; // the way is already clear
    }
    edges.add(
        dest,
        breakTime + moveCost,
        MinecraftStepType.MINE,
        state,
        restricted(agent, world, targets));
  }

  private static boolean uncoversLava(BlockView view, Cell cell) {
    for (int[] face : FACES) {
      if (view.at(cell, face[0], face[1], face[2]).isLava()) {
        return true;
      }
    }
    return false;
  }

  /**
   * A lazy supplier of the edge's combined breakability verdict: invoked only when the search pops
   * the edge, so the integration's per-block checks fire just for edges actually considered. The
   * verdict is {@code true} (restricted) if any of the cells may not be broken. {@code null} when
   * there is nothing to check.
   */
  @Nullable
  private Supplier<FutureOr<Boolean>> restricted(
      A agent, MinecraftWorld world, @Nullable List<CheckTarget> targets) {
    if (breakChecker == null || targets == null) {
      return null;
    }
    BreakChecker<A> checker = breakChecker;
    return () -> {
      List<CompletableFuture<Boolean>> checks = new ArrayList<>(targets.size());
      for (CheckTarget target : targets) {
        checks.add(checker.breakable(agent, target.cell(), world, target.block()));
      }
      return FutureOr.from(combine(checks));
    };
  }

  /**
   * Combines per-block breakability into one verdict: {@code true} if any block may not be broken.
   */
  private static CompletableFuture<Boolean> combine(List<CompletableFuture<Boolean>> breakable) {
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

  /** A block that must be cleared, captured so its breakability check can fire lazily at pop. */
  private record CheckTarget(Cell cell, MinecraftBlock block) {}
}
