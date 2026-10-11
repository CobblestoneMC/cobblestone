/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * Falling: stepping off a cardinal edge (or straight down) and dropping to the first supported cell
 * or water below. Handles drops of two or more blocks (a one-block step-down is a walk). Damage
 * beyond the safe distance is costed as a deterrent (a multiple of heal time); a fall into water
 * takes no damage; a fall onto a hazard is not offered.
 *
 * <p>Deciding <em>whether</em> anything falls from here needs only the immediate neighborhood, but
 * following a fall needs the columns beneath it — sixteen blocks down each ledge. Reading those up
 * front would be over 150 cells on every expansion, nearly all discarded on flat ground. So the
 * ledges are found in the shared view, and only their columns are fetched, in a second phase.
 */
final class Falls {

  private static final int[][] HORIZONTAL = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int MAX_FALL_SCAN = 16;

  private Falls() {}

  /** Adds the falls from {@code from} to {@code edges}, fetching the columns under any ledge. */
  static FutureOr<Edges> offer(
      MinecraftWorld world, Cell from, TraversalState state, BlockView view, Edges edges) {
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
        continue; // flat ground — a walk
      }
      if (ledges == null) {
        ledges = new ArrayList<>(HORIZONTAL.length);
      }
      ledges.add(entry);
    }
    if (ledges == null) {
      return FutureOr.of(edges); // nothing drops away from here; no deep scan needed
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
        .map(
            below -> {
              for (Cell entry : falling) {
                boolean straightDown = entry.x() == from.x() && entry.z() == from.z();
                double stepOff = straightDown ? 0.0 : MovementCosts.WALK;
                fall(from, entry, below, state, stepOff, edges);
              }
              return edges;
            });
  }

  private static void fall(
      Cell from, Cell entry, BlockView view, TraversalState state, double stepOff, Edges edges) {
    for (int d = 0; d <= MAX_FALL_SCAN; d++) {
      Cell here = entry.plus(0, -d, 0);
      MinecraftBlock block = view.at(here);
      if (!Geometry.bodyFits(view, here) && !block.isWater()) {
        return; // hit a ceiling before landing
      }
      int distance = from.y() - here.y();
      if (block.isWater()) {
        land(here, distance, stepOff, false, view, state, edges);
        return;
      }
      if (view.at(here, 0, -1, 0).isSolidTop()) {
        land(here, distance, stepOff, true, view, state, edges);
        return;
      }
    }
  }

  private static void land(
      Cell landing,
      int distance,
      double stepOff,
      boolean onSolid,
      BlockView view,
      TraversalState state,
      Edges edges) {
    if (distance < 2) {
      return; // one-block drops are walks
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
    edges.add(landing, cost, MinecraftStepType.FALL, state);
  }
}
