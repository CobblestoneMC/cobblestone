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
import org.cobblestonemc.Movement;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.MinecraftAgent;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * Stepping through a doorway — a door or fence gate — to the cell on the far side.
 *
 * <p>Both states go through this mode. Passability is a material-level fact (see {@link
 * MinecraftBlock}), so a door reads impassable whichever way it is standing, and no other mode will
 * cross one. A closed door is crossable if it opens by hand (wooden) or is iron with an activating
 * pressure plate the player is standing on — buttons/levers and distant redstone are out of scope.
 * Either way the step lands the player <em>beyond</em> the doorway, so they never end up standing
 * in a door that is about to shut.
 *
 * <p>Trapdoors are left out. They lie in the horizontal plane, so an open one stands as a wall
 * across the face it is hung on rather than opening a way through it; stepping through a doorway is
 * not the move they afford.
 *
 * @param <A> the agent type
 */
final class DoorMode<A extends MinecraftAgent> extends AbstractMinecraftMode<A> {

  private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private final boolean canOpenDoor;

  DoorMode(boolean canOpenDoor) {
    this.canOpenDoor = canOpenDoor;
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
  protected Collection<Movement<MinecraftStepPayload>> computeMovements(
      A agent, Cell from, TraversalState state, BlockView view) {
    List<Movement<MinecraftStepPayload>> moves = new ArrayList<>();
    boolean standingOnPlate = view.at(from).isPressurePlate();
    for (int[] dir : HORIZONTAL) {
      Cell doorway = from.plus(dir[0], 0, dir[1]);
      MinecraftBlock door = view.at(doorway);
      if (!door.isDoor() || door.isTrapdoor()) {
        continue;
      }
      Cell beyond = from.plus(dir[0] * 2, 0, dir[1] * 2);
      if (!view.at(doorway, 0, -1, 0).isSolidTop() || !Geometry.standable(view, beyond)) {
        continue; // no floor under the doorway, or nowhere to land on the far side
      }
      // Two blocks of ground are covered — through the doorway and out the other side — so the
      // step is priced as two walked blocks, not one.
      double walk = 2 * MovementCosts.WALK;
      if (door.isOpen()) {
        moves.add(move(beyond, walk, MinecraftStepType.WALK, state));
        continue;
      }
      if (!canOpenDoor || !(door.opensByHand() || standingOnPlate)) {
        continue; // shut, and this player has no way to open it
      }
      moves.add(move(beyond, walk + MovementCosts.OPEN_DOOR, MinecraftStepType.OPEN_DOOR, state));
    }
    return moves;
  }
}
