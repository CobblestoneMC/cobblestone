/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.cobblestonemc.ModesProvider;
import org.cobblestonemc.minecraft.BreakChecker;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftMode;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * Assembles the mode list for a search. Ability gating happens here (not inside the modes): flying
 * and boating are added only when the player can, while horse travel is always present but stays
 * dormant until the traversal state says the player is mounted.
 */
public final class MinecraftModes {

  private MinecraftModes() {}

  /**
   * Builds the modes available to {@code player} with no mining constraint.
   *
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @return the mode list
   */
  public static List<MinecraftMode<CobblestonePlayer>> forPlayer(
      CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    return forPlayer(player, excluded, null);
  }

  /**
   * Builds the modes available to {@code player}, minus any whose primary step type is excluded
   * (e.g. {@code -no-fly}), with the given breakability constraint applied to the mining mode.
   *
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @param breakChecker the injected breakability check for the mining mode, or {@code null} if no
   *     integration constrains mining
   * @return the mode list
   */
  public static List<MinecraftMode<CobblestonePlayer>> forPlayer(
      CobblestonePlayer player,
      Set<MinecraftStepType> excluded,
      BreakChecker<CobblestonePlayer> breakChecker) {
    // Free flight strictly dominates walking, falling and climbing: it is cheaper per block than
    // any of them (MovementCosts.FLY beats WALK, FALL_PER_BLOCK and CLIMB), it runs under the same
    // "not in a vehicle" gate, and its 26 neighbors are a superset of the cells they offer. So a
    // player who can fly gains nothing from carrying them, and each one costs a block fetch of its
    // whole neighborhood on every single expansion — together roughly two thirds of the blocks a
    // creative-mode search reads. Gliding is not flight (a glider cannot hover or climb), so this
    // applies to canFly only. Swimming survives because flight cannot enter water, and mining and
    // doors survive because a flier sealed in a room still needs a way out.
    boolean flies = !excluded.contains(MinecraftStepType.FLY) && player.canFly();

    List<MinecraftMode<CobblestonePlayer>> modes = new ArrayList<>();
    if (!excluded.contains(MinecraftStepType.WALK)) {
      if (!flies) {
        modes.add(new WalkMode<>());
      }
      modes.add(new DoorMode<>(!excluded.contains(MinecraftStepType.OPEN_DOOR)));
      if (!excluded.contains(MinecraftStepType.MINE)) {
        modes.add(new MineMode<>(breakChecker));
      }
    }
    if (!excluded.contains(MinecraftStepType.FALL) && !flies) {
      modes.add(new FallMode<>());
    }
    if (!excluded.contains(MinecraftStepType.SWIM)) {
      modes.add(new SwimMode<>());
    }
    if (!excluded.contains(MinecraftStepType.CLIMB) && !flies) {
      modes.add(new ClimbMode<>());
    }
    if (!excluded.contains(MinecraftStepType.HORSE)) {
      modes.add(new HorseMode<>());
    }
    if (!excluded.contains(MinecraftStepType.FLY)) {
      // Full flight fits a normal 2-tall body; an elytra glider is modelled 1 tall so it can slip
      // through a 1-block hole (an end gateway).
      if (player.canFly()) {
        modes.add(new FlyMode<>(2));
      } else if (player.canGlide()) {
        modes.add(new FlyMode<>(1));
      }
    }
    // A player already in a boat gets the mode whatever the exclusion says: every other mode
    // requires no vehicle, so without it they could not move at all.
    if ((!excluded.contains(MinecraftStepType.BOAT) && player.hasBoatInInventory())
        || player.isInBoat()) {
      modes.add(new BoatMode<>());
    }
    return modes;
  }

  /**
   * Returns a lower bound on what one block of travel can cost this player, for the admissible
   * Tier-1 edge estimate and the cold-start of the Tier-2 heuristic.
   *
   * <p>It has to be per-player, not a global constant. The cheapest movement in the game is flight,
   * and pricing a walker's routes at the flying rate makes every Tier-1 estimate wildly optimistic:
   * each leg comes back several times more expensive than promised, which immediately makes some
   * other unexplored route look cheaper, so Tier-1 re-plans and works through the alternatives one
   * costly solve at a time.
   *
   * <p>Falling is included because it is genuinely cheap per block and available to anyone — the
   * bound must hold for every path, not just the plausible ones.
   *
   * @param player the navigating player
   * @param excluded step types to leave out
   * @return the cheapest possible cost of moving one block, in seconds
   */
  public static double cheapestCostPerBlock(
      CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    double cheapest = MovementCosts.WALK;
    if (!excluded.contains(MinecraftStepType.FLY) && (player.canFly() || player.canGlide())) {
      cheapest = Math.min(cheapest, MovementCosts.FLY);
    }
    if (!excluded.contains(MinecraftStepType.FALL)) {
      cheapest = Math.min(cheapest, MovementCosts.FALL_PER_BLOCK);
    }
    if (!excluded.contains(MinecraftStepType.HORSE)) {
      cheapest = Math.min(cheapest, MovementCosts.HORSE);
    }
    // Mirrors forPlayer's condition exactly: wherever BoatMode is offered, the bound has to admit
    // its cost, or a boat step comes in under what the heuristic swore was the floor.
    if ((!excluded.contains(MinecraftStepType.BOAT) && player.hasBoatInInventory())
        || player.isInBoat()) {
      cheapest = Math.min(cheapest, MovementCosts.BOAT);
    }
    return cheapest;
  }

  /**
   * Builds a {@link ModesProvider} for a search: the player's usual modes plus — when the player
   * carries ender pearls — a goal-aware {@link EnderPearlMode} injected with each leg's target, so
   * a floating target (an end gateway) can be reached by throwing a pearl.
   *
   * @param player the player being navigated
   * @param excluded step types to leave out
   * @param breakChecker the mining breakability constraint, or {@code null}
   * @param enderPearls how many ender pearls the player holds (read on the server thread
   *     beforehand)
   * @return the modes provider
   */
  public static ModesProvider<CobblestonePlayer, MinecraftStepPayload, MinecraftWorld> providerFor(
      CobblestonePlayer player,
      Set<MinecraftStepType> excluded,
      BreakChecker<CobblestonePlayer> breakChecker,
      int enderPearls) {
    List<MinecraftMode<CobblestonePlayer>> base = forPlayer(player, excluded, breakChecker);
    if (enderPearls <= 0) {
      return ModesProvider.of(base);
    }
    return target -> {
      List<MinecraftMode<CobblestonePlayer>> withPearl = new ArrayList<>(base);
      withPearl.add(new EnderPearlMode<>(target, enderPearls));
      return withPearl;
    };
  }
}
