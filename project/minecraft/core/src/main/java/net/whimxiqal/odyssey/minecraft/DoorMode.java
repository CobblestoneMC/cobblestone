/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Movement;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.minecraft.api.MinecraftAgent;
import net.whimxiqal.odyssey.minecraft.api.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.api.MinecraftInstruction;
import net.whimxiqal.odyssey.minecraft.api.MinecraftKeys;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepType;

/**
 * Passing through a closed-but-openable door to the cell on the far side (open doors are just
 * passable and handled by {@code WalkMode}).
 *
 * <p>A closed door is passable if it opens by hand (wooden) or is iron with an activating pressure
 * plate the player is standing on — buttons/levers and distant redstone are out of scope. The step
 * lands the player beyond the doorway (so they never get stuck standing in a closed door) at a small
 * open-door cost.
 *
 * @param <A> the agent type
 */
final class DoorMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  DoorMode() {
    super(MinecraftStepType.OPEN_DOOR);
  }

  @Override
  protected boolean applies(A agent, TraversalState state) {
    return state.get(MinecraftKeys.VEHICLE) == null;
  }

  @Override
  protected Set<Cell> requiredCells(Cell from) {
    Set<Cell> cells = new HashSet<>();
    cells.add(from);
    for (int[] dir : HORIZONTAL) {
      for (int step = 1; step <= 2; step++) {
        Cell cell = from.plus(dir[0] * step, 0, dir[1] * step);
        cells.add(cell);
        cells.add(cell.plus(0, 1, 0));
        cells.add(cell.plus(0, -1, 0));
      }
    }
    return cells;
  }

  @Override
  protected Collection<Movement<MinecraftStepType, MinecraftInstruction>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepType, MinecraftInstruction>> moves = new ArrayList<>();
    boolean standingOnPlate = view.at(from).isPressurePlate();
    for (int[] dir : HORIZONTAL) {
      Cell doorway = from.plus(dir[0], 0, dir[1]);
      MinecraftBlock door = view.at(doorway);
      if (!door.isDoor() || door.isOpen()) {
        continue; // open doors are plain passable (WalkMode); only handle closed ones
      }
      boolean canOpen = door.opensByHand() || standingOnPlate;
      Cell beyond = from.plus(dir[0] * 2, 0, dir[1] * 2);
      if (!canOpen || !view.at(doorway, 0, -1, 0).isSolidTop() || !Geometry.standable(view, beyond)) {
        continue;
      }
      double cost = MovementCosts.WALK + MovementCosts.OPEN_DOOR;
      moves.add(move(beyond, cost, MinecraftStepType.OPEN_DOOR, state));
    }
    return moves;
  }
}
