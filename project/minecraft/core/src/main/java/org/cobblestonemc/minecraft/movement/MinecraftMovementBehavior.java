/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Movement;
import org.cobblestonemc.MovementBehavior;
import org.cobblestonemc.api.TraversalState;
import org.cobblestonemc.minecraft.BreakChecker;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.jetbrains.annotations.Nullable;

/** How a Minecraft player moves, each step labelled with its {@link MinecraftStepType}. */
public final class MinecraftMovementBehavior
    implements MovementBehavior<MinecraftStepPayload, MinecraftWorld> {

  /** The cells read on foot: one block out, two down to three up. */
  private static final int[][] ON_FOOT = box(1, -2, 3);

  /** The cells read in a vehicle: one block out, one down to two up. */
  private static final int[][] IN_VEHICLE = box(1, -1, 2);

  private final Abilities can;
  private final @Nullable Mining mining;
  private final int[][] onFoot;

  MinecraftMovementBehavior(Abilities can, @Nullable BreakChecker breakChecker) {
    this.can = can;
    this.mining = can.mine() ? new Mining(breakChecker) : null;
    this.onFoot = onFoot(can.doors(), can.mine());
  }

  /**
   * Builds the behavior for {@code player} with no mining constraint and no ender pearls.
   *
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @return the behavior
   */
  public static MinecraftMovementBehavior forPlayer(
      CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    return forPlayer(player, excluded, null, 0);
  }

  /**
   * Builds the behavior for {@code player}, minus any excluded step types (e.g. {@code -no-fly}).
   * The player's capabilities are read here, once, so call this on a thread allowed to read them.
   *
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @param breakChecker the injected breakability check for mining, or {@code null} if no
   *     integration constrains mining
   * @param enderPearls how many ender pearls the player holds
   * @return the behavior
   */
  public static MinecraftMovementBehavior forPlayer(
      CobblestonePlayer player,
      Set<MinecraftStepType> excluded,
      @Nullable BreakChecker breakChecker,
      int enderPearls) {
    // Flight is cheaper than walking, falling and climbing and reaches every cell they do, so a
    // flier skips them. Gliding is not flight: a glider cannot hover or climb.
    boolean flies = !excluded.contains(MinecraftStepType.FLY) && player.canFly();
    boolean walks = !excluded.contains(MinecraftStepType.WALK);
    int flightHeight = 0;
    if (!excluded.contains(MinecraftStepType.FLY)) {
      // A glider is modelled 1 tall so it can slip through a 1-block hole (an end gateway).
      if (player.canFly()) {
        flightHeight = 2;
      } else if (player.canGlide()) {
        flightHeight = 1;
      }
    }
    Abilities can =
        new Abilities(
            walks && !flies,
            walks,
            walks && !excluded.contains(MinecraftStepType.OPEN_DOOR),
            walks && !excluded.contains(MinecraftStepType.MINE),
            !excluded.contains(MinecraftStepType.FALL) && !flies,
            !excluded.contains(MinecraftStepType.SWIM),
            !excluded.contains(MinecraftStepType.CLIMB) && !flies,
            !excluded.contains(MinecraftStepType.HORSE),
            flightHeight,
            // A player already in a boat can boat whatever the exclusion says: nothing else moves
            // a vehicle, so without it they could not move at all.
            (!excluded.contains(MinecraftStepType.BOAT) && player.hasBoatInInventory())
                || player.isInBoat(),
            Math.max(0, enderPearls));
    return new MinecraftMovementBehavior(can, breakChecker);
  }

  @Override
  public FutureOr<Collection<Movement<MinecraftStepPayload>>> movements(
      Cell from, MinecraftWorld world, TraversalState state, Cell goal) {
    Edges edges = new Edges();
    MinecraftKeys.Vehicle vehicle = state.get(MinecraftKeys.VEHICLE);
    FutureOr<Edges> local;
    if (vehicle == null) {
      local =
          BlockLookup.fetch(world, from, cells(from, onFoot), goal)
              .flatMap(view -> onFoot(from, world, state, view, edges));
    } else if (vehicle == MinecraftKeys.Vehicle.BOAT && can.boat()) {
      local =
          BlockLookup.fetch(world, from, cells(from, IN_VEHICLE), goal)
              .map(
                  view -> {
                    Vehicles.boat(from, state, view, edges);
                    return edges;
                  });
    } else if (vehicle == MinecraftKeys.Vehicle.HORSE && can.horse()) {
      local =
          BlockLookup.fetch(world, from, cells(from, IN_VEHICLE), goal)
              .map(
                  view -> {
                    Vehicles.horse(from, state, view, edges);
                    return edges;
                  });
    } else {
      local = FutureOr.of(edges);
    }
    return local.map(
        collected -> {
          if (can.enderPearls() > 0) {
            EnderPearl.offer(world, from, state, goal, can.enderPearls(), collected);
          }
          return collected.movements();
        });
  }

  private FutureOr<Edges> onFoot(
      Cell from, MinecraftWorld world, TraversalState state, BlockView view, Edges edges) {
    OnFoot.offer(can, mining, world, from, state, view, edges);
    if (can.boat()) {
      Vehicles.boardBoat(from, state, view, edges);
    }
    if (can.fall()) {
      return Falls.offer(world, from, state, view, edges);
    }
    return FutureOr.of(edges);
  }

  /**
   * Returns a lower bound on the cost of one block of travel by any movement this behavior offers.
   *
   * @return the cheapest possible cost of moving one block, in seconds
   */
  public double cheapestCostPerBlock() {
    double cheapest = MovementCosts.WALK;
    if (can.flightHeight() > 0) {
      cheapest = Math.min(cheapest, MovementCosts.FLY);
    }
    if (can.fall()) {
      cheapest = Math.min(cheapest, MovementCosts.FALL_PER_BLOCK);
    }
    if (can.horse()) {
      cheapest = Math.min(cheapest, MovementCosts.HORSE);
    }
    if (can.boat()) {
      cheapest = Math.min(cheapest, MovementCosts.BOAT);
    }
    return cheapest;
  }

  /** The abilities this behavior was built with. */
  Abilities abilities() {
    return can;
  }

  /** Every offset in the view box that a rule may read on foot or in a vehicle. */
  static List<int[]> allOffsets() {
    List<int[]> all = new ArrayList<>(List.of(onFoot(true, true)));
    all.addAll(List.of(IN_VEHICLE));
    return all;
  }

  private static List<Cell> cells(Cell from, int[][] offsets) {
    List<Cell> cells = new ArrayList<>(offsets.length);
    for (int[] offset : offsets) {
      cells.add(from.plus(offset[0], offset[1], offset[2]));
    }
    return cells;
  }

  private static int[][] box(int xzRadius, int dyLow, int dyHigh) {
    List<int[]> offsets = new ArrayList<>();
    for (int dx = -xzRadius; dx <= xzRadius; dx++) {
      for (int dz = -xzRadius; dz <= xzRadius; dz++) {
        for (int dy = dyLow; dy <= dyHigh; dy++) {
          offsets.add(new int[] {dx, dy, dz});
        }
      }
    }
    return offsets.toArray(new int[0][]);
  }

  /**
   * The cells read on foot: {@link #ON_FOOT}, plus the cells two blocks out along each cardinal
   * that doorways (landing) and mining (lava checks) read.
   */
  private static int[][] onFoot(boolean doors, boolean mine) {
    if (!doors && !mine) {
      return ON_FOOT;
    }
    int dyLow = doors ? -1 : 0;
    int dyHigh = mine ? 2 : 1;
    List<int[]> offsets = new ArrayList<>(List.of(ON_FOOT));
    int[][] cardinals = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
    for (int[] cardinal : cardinals) {
      for (int dy = dyLow; dy <= dyHigh; dy++) {
        offsets.add(new int[] {cardinal[0], dy, cardinal[1]});
      }
    }
    return offsets.toArray(new int[0][]);
  }
}
