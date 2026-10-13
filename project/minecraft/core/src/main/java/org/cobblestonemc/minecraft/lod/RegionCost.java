/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.Map;
import java.util.Set;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * What crossing a region costs one agent, and which regions it can enter at all.
 *
 * <p>The only place the agent meets the {@link RegionGraph}. The graph says a region is water; this
 * says whether the agent swims it, boats it, or (with neither) cannot be there.
 *
 * <p><b>Every rate is at least {@link #cheapest()} per block of straight-line distance.</b> The
 * backward search focuses itself with that bound, and a crossing priced below it would let the
 * search settle a portal before a cheaper route to it had been seen.
 */
public final class RegionCost {

  /** {@code MovementCosts.WALK}, which a mining step pays on top of breaking. */
  private static final double MINE_WALK = 0.20;

  /** {@code MovementCosts.FALL_PER_BLOCK}, which a step down pays on top of breaking. */
  private static final double MINE_FALL = 0.18;

  private final double walk;
  private final double soul;
  private final double swim;
  private final double boat;
  private final double climb;
  private final double fly;
  private final boolean mine;
  private final double fallback;
  private final double cheapest;

  private RegionCost(Set<Medium> available, Map<Medium, Double> costs, double fallback) {
    this.walk = rate(available, costs, Medium.WALKABLE);
    this.soul = rate(available, costs, Medium.SOUL_SAND);
    this.swim = rate(available, costs, Medium.SWIMMABLE);
    this.boat = rate(available, costs, Medium.BOATABLE);
    this.climb = rate(available, costs, Medium.CLIMBABLE);
    this.fly = rate(available, costs, Medium.FLYABLE);
    this.mine = available.contains(Medium.MINEABLE);
    this.fallback = fallback;
    double least = fallback;
    for (double rate : new double[] {walk, soul, swim, boat, climb, fly}) {
      if (!Double.isNaN(rate)) {
        least = Math.min(least, rate);
      }
    }
    this.cheapest = least;
  }

  private static double rate(Set<Medium> available, Map<Medium, Double> costs, Medium medium) {
    if (!available.contains(medium)) {
      return Double.NaN;
    }
    Double override = costs.get(medium);
    return override == null ? medium.baseCostPerBlock() : Math.max(0.01, override);
  }

  /**
   * Creates a cost model for an agent with the given mediums.
   *
   * @param available what the agent can do
   * @return the cost model
   */
  public static RegionCost forMediums(Set<Medium> available) {
    return forMediums(available, Map.of());
  }

  /**
   * Creates a cost model for an agent whose equipment changes what some terrain costs them.
   *
   * @param available what the agent can do
   * @param costs per-block costs overriding {@link Medium#baseCostPerBlock()} where given
   * @return the cost model
   */
  public static RegionCost forMediums(Set<Medium> available, Map<Medium, Double> costs) {
    return new RegionCost(available, costs, CoarseCost.FALLBACK_COST_PER_BLOCK);
  }

  /**
   * Returns the cost model for a player, given the modes an operator has excluded.
   *
   * <p>Through {@link CoarseCost#mediumsFor}, so the two coarse layers cannot disagree about what a
   * player can do.
   *
   * @param player the navigating player
   * @param excluded the step types an operator has turned off
   * @return the cost model
   */
  public static RegionCost forPlayer(CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    return forMediums(CoarseCost.mediumsFor(player, excluded));
  }

  /**
   * Returns whether this agent can be inside a region of the given class.
   *
   * <p>{@link RegionClass#SOLID} always: an agent that cannot dig is still priced through rock, at
   * {@link CoarseCost#FALLBACK_COST_PER_BLOCK}. That keeps the graph connected. A gap in it — a
   * seam the builder joined too strictly, a corner it never looked across — would otherwise make
   * whole regions unreachable, and the search would spend its effort proving it rather than
   * answering. Priced at twice the cost of digging, rock is a route only when nothing else is.
   *
   * @param klass the class
   * @return {@code true} if usable
   */
  public boolean usable(RegionClass klass) {
    return switch (klass) {
      case STAND -> has(walk) || has(fly);
      case SOUL -> has(soul) || has(walk) || has(fly);
      case WATER -> has(swim) || has(boat);
      case CLIMB -> has(climb) || has(fly);
      case AIR -> has(fly);
      case SOLID -> true;
    };
  }

  /**
   * Returns the seconds to cross a region in a straight line from one point to another.
   *
   * @param klass the region's class
   * @param navigable whether a water region has a boat surface
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
      boolean navigable,
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
    return switch (klass) {
      case STAND -> least(walk, fly) * length;
      // Soul sand at the agent's own rate for it, which Soul Speed makes cheaper than ground.
      case SOUL -> least(has(soul) ? soul : walk, fly) * length;
      case WATER -> {
        if (navigable && has(boat)) {
          // A boat crosses the surface; anything vertical in the crossing is swum.
          double across = Math.sqrt(dx * dx + dz * dz);
          double up = Math.abs(dy);
          double swum = has(swim) ? swim : boat;
          yield Math.min(boat * across + swum * up, has(swim) ? swim * length : Double.MAX_VALUE);
        }
        yield (has(swim) ? swim : fallback) * length;
      }
      case CLIMB -> least(climb, fly) * length;
      case AIR -> (has(fly) ? fly : fallback) * length;
      // At least one block, however short the line: the two portals of a one-block wall sit face
      // to face, and a crossing priced by the distance between them would dig it for free.
      case SOLID ->
          mine && breakTime > 0
              ? Math.max(mining(breakTime, dx, dy, dz), breakTime + MINE_FALL)
              : fallback * Math.max(1, length);
    };
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

  /** The lower of two rates, either of which may be unavailable ({@code NaN}). */
  private double least(double a, double b) {
    if (!has(a)) {
      return has(b) ? b : fallback;
    }
    return has(b) ? Math.min(a, b) : a;
  }
}
