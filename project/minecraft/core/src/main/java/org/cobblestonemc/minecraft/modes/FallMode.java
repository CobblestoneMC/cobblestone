/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftAgent;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

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

  /**
   * The cells needed to decide <em>whether</em> anything falls from here: the immediate
   * neighborhood, not the columns beneath it.
   *
   * <p>Declaring the full scan up front — sixteen blocks down each of eight columns — was over 150
   * cells on every expansion, close to half of everything a search read, and on flat ground every
   * one of them was discarded unused: {@link #computeMovements} skips a direction the moment it
   * finds standable footing there. So the deep columns are fetched in a second phase, only for the
   * directions that turned out to be ledges. See {@link #movements}.
   */
  @Override
  protected Set<Cell> requiredCells(Cell from) {
    return Neighborhood.box(from, 1, -1, 2);
  }

  @Override
  protected FutureOr<Collection<Movement<MinecraftStepPayload>>> movements(
      A agent, Cell from, MinecraftWorld world, TraversalState state, BlockView view) {
    List<Cell> ledges = null;
    for (int[] dir : HORIZONTAL) {
      Cell entry = from.plus(dir[0], 0, dir[1]);
      if (!Geometry.bodyFits(view, entry)) {
        continue; // wall in the way
      }
      boolean straightDown = dir[0] == 0 && dir[1] == 0;
      if (straightDown && view.at(from, 0, -1, 0).isSolidTop()) {
        continue; // standing on solid ground, not falling straight down
      }
      if (!straightDown && Geometry.standable(view, entry)) {
        continue; // flat ground — WalkMode handles it
      }
      if (ledges == null) {
        ledges = new ArrayList<>(HORIZONTAL.length);
      }
      ledges.add(entry);
    }
    if (ledges == null) {
      return FutureOr.of(List.of()); // nothing drops away from here; no deep scan needed
    }

    List<Cell> falling = ledges;
    Set<Cell> columns = new HashSet<>();
    for (Cell entry : falling) {
      // From one above the ledge (the scan's clearance check reads the cell above each candidate
      // landing) down past the deepest landing it could find.
      for (int d = -1; d <= MAX_FALL_SCAN + 1; d++) {
        columns.add(entry.plus(0, -d, 0));
      }
    }
    return BlockLookup.fetchApart(world, columns, from)
        .map(below -> computeFalls(from, falling, view, below, state));
  }

  /**
   * Builds the falls off the given ledges. Reads the shallow {@code view} for the step-off cells
   * and the second-phase {@code below} for the columns underneath.
   */
  private Collection<Movement<MinecraftStepPayload>> computeFalls(
      Cell from, List<Cell> ledges, BlockView view, BlockView below, TraversalState state) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
    for (Cell entry : ledges) {
      boolean straightDown = entry.x() == from.x() && entry.z() == from.z();
      double stepOff = straightDown ? 0.0 : MovementCosts.WALK;
      addFall(from, entry, below, state, stepOff, moves);
    }
    return moves;
  }

  private void addFall(
      Cell from,
      Cell entry,
      BlockView view,
      TraversalState state,
      double stepOff,
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
      Cell landing,
      int distance,
      double stepOff,
      boolean onSolid,
      BlockView view,
      TraversalState state,
      List<Movement<MinecraftStepPayload>> moves) {
    if (distance < 2) {
      return; // one-block drops are WalkMode's job
    }
    if (view.at(landing).isDangerous()) {
      return; // don't fall onto lava/fire
    }
    double cost = stepOff + MovementCosts.FALL_PER_BLOCK * distance;
    if (onSolid) {
      double damageHalfHearts = Math.max(0, distance - MovementCosts.SAFE_FALL_BLOCKS);
      cost +=
          MovementCosts.DAMAGE_COST_MULTIPLIER
              * MovementCosts.HEAL_SECONDS_PER_HALF_HEART
              * damageHalfHearts;
    }
    moves.add(move(landing, cost, MinecraftStepType.FALL, state));
  }
}
