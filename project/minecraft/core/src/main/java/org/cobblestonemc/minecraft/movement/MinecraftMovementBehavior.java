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
import org.cobblestonemc.minecraft.ChunkSource;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftKeys;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.jetbrains.annotations.Nullable;

/** How a Minecraft player moves, each step labelled with its {@link MinecraftStepType}. */
public final class MinecraftMovementBehavior
    implements MovementBehavior<MinecraftStepPayload, MinecraftWorld> {

  /** The cells read dismounted: one block out, two down to three up. */
  private static final int[][] DISMOUNTED = box(1, -2, 3);

  /** The cells read in a vehicle: one block out, one down to two up. */
  private static final int[][] IN_VEHICLE = box(1, -1, 2);

  private final ChunkSource chunks;
  private final Abilities can;
  private final @Nullable Mining mining;
  private final int[][] dismountedOffsets;
  private final TraversalState initialState;

  private MinecraftMovementBehavior(
      ChunkSource chunks,
      Abilities can,
      @Nullable BreakChecker breakChecker,
      TraversalState initialState) {
    this.chunks = chunks;
    this.can = can;
    this.initialState = initialState;
    this.mining = can.mine() ? new Mining(breakChecker) : null;
    this.dismountedOffsets = dismountedOffsets(can.doors(), can.mine());
  }

  /**
   * Builds the behavior for {@code player} with no mining constraint and no ender pearls.
   *
   * @param chunks where the search reads blocks from
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @return the behavior
   */
  public static MinecraftMovementBehavior forPlayer(
      ChunkSource chunks, CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    return forPlayer(chunks, player, excluded, null, 0);
  }

  /**
   * Builds the behavior for {@code player}, minus any excluded step types (e.g. {@code -no-fly}).
   * The player's capabilities are read here, once, so call this on a thread allowed to read them.
   *
   * @param chunks where the search reads blocks from: its own, so build one behavior per search.
   *     The behavior holds it, and with it every cached chunk, for as long as the behavior is
   *     reachable, so do not keep the behavior, or a handle to its search, past the search
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @param breakChecker the injected breakability check for mining, or {@code null} if no
   *     integration constrains mining
   * @param enderPearls how many ender pearls the player holds
   * @return the behavior
   */
  public static MinecraftMovementBehavior forPlayer(
      ChunkSource chunks,
      CobblestonePlayer player,
      Set<MinecraftStepType> excluded,
      @Nullable BreakChecker breakChecker,
      int enderPearls) {
    // Flight is cheaper than walking, falling and climbing and reaches every cell they do, so a
    // flier skips them. Gliding is not flight: a glider cannot hover or climb.
    boolean flies = !excluded.contains(MinecraftStepType.FLY) && player.canFly();
    boolean walks = !excluded.contains(MinecraftStepType.WALK);
    boolean boats = !excluded.contains(MinecraftStepType.BOAT);
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
            boats && player.hasBoatInInventory(),
            boats && (player.hasBoatInInventory() || player.isInBoat()),
            Math.max(0, enderPearls));
    // A player already in a boat starts in it. Getting out is always open to them, so excluding
    // boats leaves them only that.
    TraversalState initialState =
        player.isInBoat()
            ? TraversalState.DEFAULT.with(MinecraftKeys.VEHICLE, MinecraftKeys.Vehicle.BOAT)
            : TraversalState.DEFAULT;
    return new MinecraftMovementBehavior(chunks, can, breakChecker, initialState);
  }

  @Override
  public FutureOr<Collection<Movement<MinecraftStepPayload>>> movements(
      Cell from, MinecraftWorld world, TraversalState state, Cell goal) {
    Edges edges = new Edges();
    MinecraftKeys.Vehicle vehicle = state.get(MinecraftKeys.VEHICLE);
    FutureOr<Edges> local;
    if (vehicle == null) {
      local =
          BlockLookup.fetch(chunks, world, from, cells(from, dismountedOffsets), goal)
              .flatMap(view -> dismounted(from, world, state, view, edges));
    } else if (vehicle == MinecraftKeys.Vehicle.BOAT) {
      // Getting out is always open to a player in a boat; only paddling needs the boat ability.
      local =
          BlockLookup.fetch(chunks, world, from, cells(from, IN_VEHICLE), goal)
              .map(
                  view -> {
                    if (can.paddle()) {
                      Vehicles.boat(from, state, view, edges);
                    }
                    Vehicles.leaveBoat(can, from, state, view, edges);
                    return edges;
                  });
    } else if (vehicle == MinecraftKeys.Vehicle.HORSE && can.horse()) {
      local =
          BlockLookup.fetch(chunks, world, from, cells(from, IN_VEHICLE), goal)
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
            EnderPearl.offer(chunks, world, from, state, goal, can.enderPearls(), collected);
          }
          return collected.movements();
        });
  }

  private FutureOr<Edges> dismounted(
      Cell from, MinecraftWorld world, TraversalState state, BlockView view, Edges edges) {
    Dismounted.offer(can, mining, world, from, state, view, edges);
    if (can.boardBoat()) {
      Vehicles.boardBoat(from, state, view, edges);
    }
    if (can.fall()) {
      return Falls.offer(chunks, world, from, state, view, edges);
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
    if (can.paddle()) {
      cheapest = Math.min(cheapest, MovementCosts.BOAT);
    }
    return cheapest;
  }

  @Override
  public TraversalState initialState() {
    return initialState;
  }

  /** The abilities this behavior was built with. */
  Abilities abilities() {
    return can;
  }

  /** Every offset in the view box that a rule may read, dismounted or in a vehicle. */
  static List<int[]> allOffsets() {
    List<int[]> all = new ArrayList<>(List.of(dismountedOffsets(true, true)));
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
   * The cells read dismounted: {@link #DISMOUNTED}, plus the cells two blocks out along each
   * cardinal that doorways (landing) and mining (lava checks) read.
   */
  private static int[][] dismountedOffsets(boolean doors, boolean mine) {
    if (!doors && !mine) {
      return DISMOUNTED;
    }
    int dyLow = doors ? -1 : 0;
    int dyHigh = mine ? 2 : 1;
    List<int[]> offsets = new ArrayList<>(List.of(DISMOUNTED));
    int[][] cardinals = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
    for (int[] cardinal : cardinals) {
      for (int dy = dyLow; dy <= dyHigh; dy++) {
        offsets.add(new int[] {cardinal[0], dy, cardinal[1]});
      }
    }
    return offsets.toArray(new int[0][]);
  }
}
