/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.Set;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.api.MinecraftStepType;

/**
 * One agent, as the region layer sees it: what crossing each {@link RegionClass} costs it, and
 * which it can enter at all.
 *
 * <p><b>Terrain is tagged; the agent prices it.</b> A region class only says what the terrain is:
 * soul sand, soul soil, a water surface. Everything about cost is here, worked out once per search
 * from the player: their Soul Speed level, whether they carry a boat or can fly, what an operator
 * has excluded. A crossing costs the agent's cheapest way across the class, so a player in Soul
 * Speed boots crosses soul sand faster than ground and a player without them crosses it slower.
 * Every class is priced in one exhaustive {@code switch}, so a new class does not compile until
 * this says what it costs.
 *
 * <p><b>The rates mirror {@code MovementCosts}.</b> They are what the fine search charges for a
 * block of that terrain, not independent guesses, because any gap between the two becomes a flat
 * bias in every estimate. They had drifted once: walking was 0.215 against the search's 0.20 and
 * swimming 0.45 against 0.30, which over-priced dry ground by 7.5% and water by 50% everywhere at
 * once. The fine search may grow costs this layer does not model; a change to either is checked
 * with {@code bench verify} and a sweep.
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
   * <p>Twice a nominal mining rate of 1.4 s a block. Finite on purpose: infinity would turn a gap
   * in the graph into a fence, and the estimate has to be able to be wrong without making a
   * solvable route unreachable.
   */
  public static final double FALLBACK_COST_PER_BLOCK = 2.8;

  /** {@code MovementCosts.WALK}. */
  private static final double WALK = 0.20;

  /** {@code MovementCosts.SWIM}. */
  private static final double SWIM = 0.30;

  /** {@code MovementCosts.BOAT}. */
  private static final double BOAT = 0.15;

  /** {@code MovementCosts.CLIMB}. */
  private static final double CLIMB = 0.40;

  /** {@code MovementCosts.FLY}. */
  private static final double FLY = 0.08;

  /** {@code MovementCosts.FALL_PER_BLOCK}, which a mining step down pays on top of breaking. */
  private static final double FALL = 0.18;

  /** Soul sand's speed factor, as the platforms report it: a walker without Soul Speed drags. */
  private static final double SOUL_SAND_SPEED = 0.4;

  /** Per class, by ordinal: the agent's rate in a straight line, or {@code NaN} if it has none. */
  private final double[] rates = new double[RegionClass.ALL.length];

  /** The agent's rate across a water surface by boat, or {@code NaN} without one. */
  private final double boat;

  private final boolean mine;
  private final double cheapest;

  private RegionCost(
      double walk,
      double soulSpeed,
      double swim,
      double boat,
      double climb,
      double fly,
      boolean mine) {
    this.boat = boat;
    this.mine = mine;
    double least = FALLBACK_COST_PER_BLOCK;
    for (RegionClass klass : RegionClass.ALL) {
      double rate =
          switch (klass) {
            case STAND -> least(walk, fly);
            case SOUL_SAND -> least(least(walk / SOUL_SAND_SPEED, soulSpeed), fly);
            case SOUL_SOIL -> least(least(walk, soulSpeed), fly);
            case WATER, WATER_SURFACE -> swim;
            case CLIMB -> least(climb, fly);
            case AIR -> fly;
            case SOLID -> Double.NaN; // priced by break time; see traverse
          };
      rates[klass.ordinal()] = rate;
      if (has(rate)) {
        least = Math.min(least, rate);
      }
    }
    if (has(boat)) {
      least = Math.min(least, boat);
    }
    this.cheapest = least;
  }

  /**
   * Returns the cost model for a player, given the step types an operator has excluded.
   *
   * <p>The one place a {@link CobblestonePlayer} becomes region costs, read from the same player
   * and exclusions the fine search is built from, so the two cannot disagree about what a player is
   * able to do. A way of moving the exclusions forbid is not priced: pricing it would steer the
   * estimate toward routes the fine search is not allowed to take. Public so the benchmark builds
   * its agents here too.
   *
   * @param player the navigating player
   * @param excluded the step types an operator has turned off
   * @return the cost model
   */
  public static RegionCost forPlayer(CobblestonePlayer player, Set<MinecraftStepType> excluded) {
    boolean walks = !excluded.contains(MinecraftStepType.WALK);
    int soulSpeed = walks ? player.soulSpeedLevel() : 0;
    boolean flies =
        !excluded.contains(MinecraftStepType.FLY) && (player.canFly() || player.canGlide());
    boolean boats =
        !excluded.contains(MinecraftStepType.BOAT)
            && (player.hasBoatInInventory() || player.isInBoat());
    return new RegionCost(
        walks ? WALK : Double.NaN,
        soulSpeed > 0 ? WALK / soulSpeedFactor(soulSpeed) : Double.NaN,
        excluded.contains(MinecraftStepType.SWIM) ? Double.NaN : SWIM,
        boats ? BOAT : Double.NaN,
        excluded.contains(MinecraftStepType.CLIMB) ? Double.NaN : CLIMB,
        flies ? FLY : Double.NaN,
        !excluded.contains(MinecraftStepType.MINE));
  }

  /**
   * How much faster Soul Speed boots of a level walk on soul sand and soul soil, mirroring {@code
   * MovementCosts.soulSpeedFactor}: the enchantment adds {@code 0.03 * (1 + 0.35 * level)} to a
   * base movement speed of 0.1, and lifts soul sand's drag.
   */
  private static double soulSpeedFactor(int level) {
    return 1 + 0.3 * (1 + 0.35 * level);
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
        || has(rates[klass.ordinal()])
        || (klass == RegionClass.WATER_SURFACE && has(boat));
  }

  /**
   * Returns the seconds to cross a region in a straight line from one point to another, by the
   * cheapest way the agent has across it.
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
          ? Math.max(mining(breakTime, dx, dy, dz), breakTime + FALL)
          : FALLBACK_COST_PER_BLOCK * Math.max(1, length);
    }
    double rate = rates[klass.ordinal()];
    double cost = (has(rate) ? rate : FALLBACK_COST_PER_BLOCK) * length;
    if (klass == RegionClass.WATER_SURFACE && has(boat)) {
      // A boat crosses the surface; anything vertical in the crossing, where a surface steps down
      // a fall of water, is swum.
      double across = Math.sqrt(dx * dx + dz * dz);
      double boated = boat * across + (has(rate) ? rate : boat) * Math.abs(dy);
      cost = has(rate) ? Math.min(cost, boated) : boated;
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
    double flat = 2 * breakTime + WALK;
    if (dy > 0) {
      return dy * (3 * breakTime + WALK * Math.sqrt(2)) + Math.max(0, horizontal - dy) * flat;
    }
    return -dy * (breakTime + FALL) + horizontal * flat;
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
    return FALLBACK_COST_PER_BLOCK;
  }

  private static boolean has(double rate) {
    return !Double.isNaN(rate);
  }

  /** The lower of two rates, either of which may be missing ({@code NaN}). */
  private static double least(double a, double b) {
    if (!has(a)) {
      return b;
    }
    return has(b) ? Math.min(a, b) : a;
  }
}
