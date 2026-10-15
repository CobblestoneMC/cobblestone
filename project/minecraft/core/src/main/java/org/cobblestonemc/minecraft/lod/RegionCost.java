/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * What crossing a region costs one agent, and which regions it can enter at all.
 *
 * <p>The only place the agent meets the {@link RegionGraph}. The graph says a region is a water
 * surface; this says whether the agent boats it, swims it, or (with neither) cannot be there, by
 * matching the agent's {@link Capability capabilities} against the ones the {@link RegionClass}
 * lists and taking the cheapest.
 *
 * <p><b>Every rate is at least {@link #cheapest()} per block of straight-line distance.</b> The
 * backward search focuses itself with that bound, and a crossing priced below it would let the
 * search settle a portal before a cheaper route to it had been seen.
 */
public final class RegionCost {

  /**
   * What a block costs where the agent has no way through but the graph needs a price: rock, for an
   * agent that cannot dig.
   *
   * <p>Twice the nominal mining rate. Finite on purpose: infinity would turn a gap in the graph
   * into a fence, and the estimate has to be able to be wrong without making a solvable route
   * unreachable.
   */
  public static final double FALLBACK_COST_PER_BLOCK = 2 * Capability.MINE.baseCostPerBlock();

  /** {@code MovementCosts.WALK}, which a mining step pays on top of breaking. */
  private static final double MINE_WALK = 0.20;

  /** {@code MovementCosts.FALL_PER_BLOCK}, which a step down pays on top of breaking. */
  private static final double MINE_FALL = 0.18;

  /** Per capability, by ordinal: the agent's rate per block, or {@code NaN} if it lacks it. */
  private final double[] rates;

  /**
   * Per class, by ordinal: the agent's cheapest straight-line rate across it, or {@code NaN} if
   * none of its capabilities cross it. Mining and boating are priced by shape rather than by a rate
   * alone, and are left out.
   */
  private final double[] straight;

  private final boolean mine;
  private final double fallback;
  private final double cheapest;

  private RegionCost(Set<Capability> available, Map<Capability, Double> costs, double fallback) {
    this.rates = new double[Capability.ALL.length];
    for (Capability capability : Capability.ALL) {
      Double override = costs.get(capability);
      rates[capability.ordinal()] =
          !available.contains(capability)
              ? Double.NaN
              : override == null ? capability.baseCostPerBlock() : Math.max(0.01, override);
    }
    this.mine = available.contains(Capability.MINE);
    this.fallback = fallback;
    this.straight = new double[RegionClass.ALL.length];
    double least = fallback;
    for (RegionClass klass : RegionClass.ALL) {
      double best = Double.NaN;
      for (Capability capability : Capability.ALL) {
        double rate = rates[capability.ordinal()] * klass.factor(capability);
        if (Double.isNaN(rate) || capability == Capability.MINE) {
          continue;
        }
        least = Math.min(least, rate);
        if (capability != Capability.BOAT) {
          best = Double.isNaN(best) ? rate : Math.min(best, rate);
        }
      }
      straight[klass.ordinal()] = best;
    }
    this.cheapest = least;
  }

  /**
   * Creates a cost model for an agent with the given capabilities, at their base rates.
   *
   * @param available what the agent can do
   * @return the cost model
   */
  public static RegionCost forCapabilities(Set<Capability> available) {
    return forCapabilities(available, Map.of());
  }

  /**
   * Creates a cost model for an agent whose equipment changes what some capabilities cost them.
   *
   * @param available what the agent can do
   * @param costs per-block costs overriding {@link Capability#baseCostPerBlock()} where given
   * @return the cost model
   */
  public static RegionCost forCapabilities(
      Set<Capability> available, Map<Capability, Double> costs) {
    return new RegionCost(available, costs, FALLBACK_COST_PER_BLOCK);
  }

  /**
   * Returns the cost model for a player, given the modes an operator has excluded.
   *
   * @param player the navigating player
   * @param excluded the step types an operator has turned off
   * @return the cost model
   */
  public static RegionCost forPlayer(CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    return forCapabilities(capabilitiesFor(player, excluded), ratesFor(player));
  }

  /**
   * Returns the capabilities a player has, given what an operator has excluded.
   *
   * <p>The one place a {@link CobblestonePlayer} becomes capabilities, so the estimate and the fine
   * search cannot disagree about what a player is able to do. A capability the exclusions forbid is
   * dropped: leaving it in prices routes the search is not allowed to take, and the estimate then
   * steers toward paths it can never find. Public so the benchmark derives its agents here too.
   *
   * @param player the navigating player
   * @param excluded the step types an operator has turned off
   * @return the capabilities
   */
  public static Set<Capability> capabilitiesFor(
      CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    EnumSet<Capability> capabilities = EnumSet.copyOf(survival());
    if (excluded.contains(MinecraftStepType.MINE)) {
      capabilities.remove(Capability.MINE);
    }
    if (excluded.contains(MinecraftStepType.SWIM)) {
      capabilities.remove(Capability.SWIM);
    }
    if (excluded.contains(MinecraftStepType.WALK)) {
      capabilities.remove(Capability.WALK);
    } else if (player.soulSpeedLevel() > 0) {
      // Soul Speed is a way of walking, so it goes with walking.
      capabilities.add(Capability.SOUL_SPEED);
    }
    if (excluded.contains(MinecraftStepType.CLIMB)) {
      capabilities.remove(Capability.CLIMB);
    }
    if (!excluded.contains(MinecraftStepType.FLY) && (player.canFly() || player.canGlide())) {
      capabilities.add(Capability.FLY);
    }
    if (!excluded.contains(MinecraftStepType.BOAT)
        && (player.hasBoatInInventory() || player.isInBoat())) {
      capabilities.add(Capability.BOAT);
    }
    return capabilities;
  }

  /**
   * Returns the rates a player's equipment sets for their capabilities, where it differs from the
   * base: Soul Speed, by the level of the boots.
   *
   * @param player the navigating player
   * @return the rates, by capability
   */
  public static Map<Capability, Double> ratesFor(CobblestonePlayer player) {
    int level = player.soulSpeedLevel();
    if (level <= 0) {
      return Map.of();
    }
    return Map.of(
        Capability.SOUL_SPEED, Capability.WALK.baseCostPerBlock() / soulSpeedFactor(level));
  }

  /**
   * How much faster Soul Speed boots of a level walk on the blocks they work on, mirroring {@code
   * MovementCosts.soulSpeedFactor}: the enchantment adds {@code 0.03 * (1 + 0.35 * level)} to a
   * base movement speed of 0.1, and lifts soul sand's drag.
   */
  private static double soulSpeedFactor(int level) {
    return 1 + 0.3 * (1 + 0.35 * level);
  }

  /**
   * Returns the capabilities a plain survival player has: everything but flight, boats and Soul
   * Speed.
   *
   * @return the default capability set
   */
  public static Set<Capability> survival() {
    return EnumSet.of(Capability.WALK, Capability.SWIM, Capability.CLIMB, Capability.MINE);
  }

  /**
   * Returns whether this agent can be inside a region of the given class.
   *
   * <p>{@link RegionClass#SOLID} always: an agent that cannot dig is still priced through rock, at
   * {@link #FALLBACK_COST_PER_BLOCK}. That keeps the graph connected. A gap in it — a seam the
   * builder joined too strictly, a corner it never looked across — would otherwise make whole
   * regions unreachable, and the search would spend its effort proving it rather than answering.
   * Priced at twice the cost of digging, rock is a route only when nothing else is.
   *
   * @param klass the class
   * @return {@code true} if usable
   */
  public boolean usable(RegionClass klass) {
    return klass == RegionClass.SOLID
        || has(straight[klass.ordinal()])
        || has(rate(Capability.BOAT) * klass.factor(Capability.BOAT));
  }

  /**
   * Returns the seconds to cross a region in a straight line from one point to another, by the
   * cheapest capability the agent has for it.
   *
   * @param klass the region's class
   * @param breakTime the region's mean break time, for rock
   * @param fx where the crossing starts, X
   * @param fy where the crossing starts, Y
   * @param fz where the crossing starts, Z
   * @param tx where it ends, X
   * @param ty where it ends, Y
   * @param tz where it ends, Z
   * @return the cost in seconds
   */
  public double traverse(
      RegionClass klass,
      double breakTime,
      double fx,
      double fy,
      double fz,
      double tx,
      double ty,
      double tz) {
    double dx = tx - fx;
    double dy = ty - fy;
    double dz = tz - fz;
    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (klass == RegionClass.SOLID) {
      // At least one block, however short the line: the two portals of a one-block wall sit face
      // to face, and a crossing priced by the distance between them would dig it for free.
      return mine && breakTime > 0
          ? Math.max(mining(breakTime, dx, dy, dz), breakTime + MINE_FALL)
          : fallback * Math.max(1, length);
    }
    double rate = straight[klass.ordinal()];
    double cost = (has(rate) ? rate : fallback) * length;
    double boat = rate(Capability.BOAT) * klass.factor(Capability.BOAT);
    if (has(boat)) {
      // A boat crosses the surface; anything vertical in the crossing, where a surface steps down
      // a fall of water, is swum.
      double across = Math.sqrt(dx * dx + dz * dz);
      double up = Math.abs(dy) * (has(rate) ? rate : boat);
      cost = Math.min(has(rate) ? cost : Double.MAX_VALUE, boat * across + up);
    }
    return cost;
  }

  /**
   * Mirrors {@code MineMode}: a horizontal step breaks the two blocks a body occupies and walks; a
   * step up breaks three and walks a diagonal, gaining a block of ground for free; a step down
   * breaks one and falls. Mining moves along the four horizontal axes only, so horizontal distance
   * is Manhattan.
   */
  private static double mining(double breakTime, double dx, double dy, double dz) {
    double horizontal = Math.abs(dx) + Math.abs(dz);
    double flat = 2 * breakTime + MINE_WALK;
    if (dy > 0) {
      return dy * (3 * breakTime + MINE_WALK * Math.sqrt(2)) + Math.max(0, horizontal - dy) * flat;
    }
    return -dy * (breakTime + MINE_FALL) + horizontal * flat;
  }

  /**
   * Returns the least any crossing can cost per block.
   *
   * @return the cheapest rate, in seconds per block
   */
  public double cheapest() {
    return cheapest;
  }

  /**
   * Returns what a crossing the graph cannot price costs per block: rock without a pickaxe.
   *
   * @return the fallback rate, in seconds per block
   */
  public double fallback() {
    return fallback;
  }

  private static boolean has(double rate) {
    return !Double.isNaN(rate);
  }

  /** The agent's rate for a capability, or {@code NaN} if it lacks it. */
  private double rate(Capability capability) {
    return rates[capability.ordinal()];
  }
}
