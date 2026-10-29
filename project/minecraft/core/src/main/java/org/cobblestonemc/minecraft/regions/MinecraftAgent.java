/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import java.util.Set;
import org.cobblestonemc.minecraft.CobblestonePlayer;
import org.cobblestonemc.minecraft.MinecraftWorld.Environment;
import org.cobblestonemc.minecraft.api.MinecraftStepType;
import org.cobblestonemc.regions.RegionAgent;

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
 * <p><b>Its own rates, on purpose.</b> They start from what the fine search charges a block of each
 * terrain ({@code MovementCosts}), but they are copies, not references, and are meant to drift
 * apart where the two levels see differently. The fine search will grow costs this layer cannot see
 * — a turn, a wall close by, danger nearby — that adjust its base walk, while a region is priced
 * only by its class; neither set of numbers is a precise constant the other must match. What keeps
 * them honest is measurement: {@code bench verify-regions} compares these estimates with the fine
 * search's true costs, and a drift of either side shows up there as the suite's ratios moving.
 * Change either side, run {@code ./gradlew verifyRegions} and a sweep.
 *
 * <p><b>Every rate is at least {@link #cheapest()} per block of straight-line distance.</b> The
 * backward search focuses itself with that bound, and a crossing priced below it would let the
 * search settle a portal before a cheaper route to it had been seen.
 */
public final class MinecraftAgent implements RegionAgent<RegionClass> {

  /**
   * What a block costs where the agent has no way through but the graph needs a price: rock, for an
   * agent that cannot dig.
   *
   * <p>Fourteen blocks of walking a block, so rock is a route only when nothing else is. Finite on
   * purpose: infinity would turn a gap in the graph into a fence, and the estimate has to be able
   * to be wrong without making a solvable route unreachable. Not a mining rate: an agent that digs
   * pays the break time of what it digs instead, which for stone is more than this.
   */
  public static final double FALLBACK_COST_PER_BLOCK = 2.8;

  /** Walking, from {@code MovementCosts.WALK}; see the class comment on why it is a copy. */
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

  /**
   * The deepest fall onto ground or a ladder taken without damage: {@code
   * MovementCosts.SAFE_FALL_BLOCKS}. FallMode will go further at the price of damage, which it
   * charges at several seconds a block; a route that needs that is rare, and pricing it as a cheap
   * drop would invite the fine search to every cliff edge. Feather Falling raises it.
   */
  private static final int SAFE_FALL = 3;

  /** Soul sand's speed factor, as the platforms report it: a walker without Soul Speed drags. */
  private static final double SOUL_SAND_SPEED = 0.4;

  /** Per class, by ordinal: the agent's rate in a straight line, or {@code NaN} if it has none. */
  private final double[] rates = new double[RegionClass.values().length];

  /** The agent's rate across a water surface by boat, or {@code NaN} without one. */
  private final double boat;

  private final boolean mine;

  /** The environment of the world it is priced in, which says what its soft rock is. */
  private final Environment environment;

  private final double cheapest;

  private MinecraftAgent(
      double walk,
      double soulSpeed,
      double swim,
      double boat,
      double climb,
      double fly,
      boolean mine,
      Environment environment) {
    this.boat = boat;
    this.mine = mine;
    this.environment = environment;
    double least = FALLBACK_COST_PER_BLOCK;
    for (RegionClass clazz : RegionClass.values()) {
      double rate =
          switch (clazz) {
            case STAND -> least(walk, fly);
            case SOUL_SAND -> least(least(walk / SOUL_SAND_SPEED, soulSpeed), fly);
            case SOUL_SOIL -> least(least(walk, soulSpeed), fly);
            case WATER, WATER_SURFACE -> swim;
            case CLIMB -> least(climb, fly);
            case AIR -> fly;
            // Priced by break time and direction; see traverse.
            case SOFT, SOFT_ROCK, ROCK, MIXED_ROCK, HARD_ROCK, VERY_HARD_ROCK, LOOSE, WOODEN ->
                Double.NaN;
          };
      rates[clazz.ordinal()] = rate;
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
   * Returns the cost model for a player in a world, given the step types an operator has excluded.
   *
   * <p>The one place a {@link CobblestonePlayer} becomes region costs, read from the same player
   * and exclusions the fine search is built from, so the two cannot disagree about what a player is
   * able to do. A way of moving the exclusions forbid is not priced: pricing it would steer the
   * estimate toward routes the fine search is not allowed to take. Public so the benchmark builds
   * its agents here too.
   *
   * @param player the navigating player
   * @param excluded the step types an operator has turned off
   * @param environment the environment of the world the estimate is for: the Nether's soft rock is
   *     netherrack, softer than sandstone
   * @return the cost model
   */
  public static MinecraftAgent forPlayer(
      CobblestonePlayer player, Set<MinecraftStepType> excluded, Environment environment) {
    boolean walks = !excluded.contains(MinecraftStepType.WALK);
    int soulSpeed = walks ? player.soulSpeedLevel() : 0;
    boolean flies =
        !excluded.contains(MinecraftStepType.FLY) && (player.canFly() || player.canGlide());
    boolean boats =
        !excluded.contains(MinecraftStepType.BOAT)
            && (player.hasBoatInInventory() || player.isInBoat());
    return new MinecraftAgent(
        walks ? WALK : Double.NaN,
        soulSpeed > 0 ? WALK / soulSpeedFactor(soulSpeed) : Double.NaN,
        excluded.contains(MinecraftStepType.SWIM) ? Double.NaN : SWIM,
        boats ? BOAT : Double.NaN,
        excluded.contains(MinecraftStepType.CLIMB) ? Double.NaN : CLIMB,
        flies ? FLY : Double.NaN,
        !excluded.contains(MinecraftStepType.MINE),
        environment);
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
   * <p>A {@linkplain RegionClass#solid() solid} class always: an agent that cannot dig is still
   * priced through rock, at {@link #FALLBACK_COST_PER_BLOCK}. That keeps the graph connected. A gap
   * in it — a seam the builder joined too strictly, a corner it never looked across — would
   * otherwise make whole regions unreachable, and the search would spend its effort proving it
   * rather than answering. Priced at fourteen blocks of walking a block, rock is a route only when
   * nothing else is.
   *
   * @param clazz the class
   * @return {@code true} if usable
   */
  @Override
  public boolean usable(RegionClass clazz) {
    return clazz.solid()
        || has(rates[clazz.ordinal()])
        || (clazz == RegionClass.WATER_SURFACE && has(boat));
  }

  /**
   * Returns the seconds to cross a region in a straight line from one point to another, by the
   * cheapest way the agent has across it.
   *
   * @param clazz the region's class
   * @param fx where the crossing starts, X
   * @param fy where the crossing starts, Y
   * @param fz where the crossing starts, Z
   * @param tx where it ends, X
   * @param ty where it ends, Y
   * @param tz where it ends, Z
   * @return the cost in seconds
   */
  @Override
  public double traverse(
      RegionClass clazz, double fx, double fy, double fz, double tx, double ty, double tz) {
    double dx = tx - fx;
    double dy = ty - fy;
    double dz = tz - fz;
    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (clazz.solid()) {
      double breakTime = breakTime(clazz);
      // At least one block, however short the line: the two portals of a one-block wall sit face
      // to face, and a crossing priced by the distance between them would dig it for free.
      return mine
          ? Math.max(mining(breakTime, dx, dy, dz), breakTime + FALL)
          : FALLBACK_COST_PER_BLOCK * Math.max(1, length);
    }
    double rate = rates[clazz.ordinal()];
    double cost = (has(rate) ? rate : FALLBACK_COST_PER_BLOCK) * length;
    if (clazz == RegionClass.WATER_SURFACE && has(boat)) {
      // A boat crosses the surface; anything vertical in the crossing, where a surface steps down
      // a fall of water, is swum.
      double across = Math.sqrt(dx * dx + dz * dz);
      double boated = boat * across + (has(rate) ? rate : boat) * Math.abs(dy);
      cost = has(rate) ? Math.min(cost, boated) : boated;
    }
    return cost;
  }

  /**
   * The seconds to break a typical block of a class of breakable material, in this agent's world.
   *
   * <p>What the fine search charges today, which knows no tools: {@code MinecraftBlock
   * #breakTimeSeconds()} is the block's hardness times 1.5, whatever the agent carries. Tools
   * change this and nothing stored: a better pickaxe makes rock faster for the agent holding it,
   * here.
   */
  private double breakTime(RegionClass clazz) {
    return MinecraftTerrain.hardness(clazz, environment) * 1.5;
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

  @Override
  public double cheapest() {
    return cheapest;
  }

  @Override
  public double fallback() {
    return FALLBACK_COST_PER_BLOCK;
  }

  /**
   * Water breaks any fall; ground and ladders only one of {@link #SAFE_FALL} blocks or less. The
   * fall itself adds nothing: the height fallen is priced in crossing the region landed in.
   */
  @Override
  public double fall(RegionClass clazz, int depth) {
    return switch (clazz.body()) {
      case WATER -> 0;
      case GROUND, CLIMB -> depth <= SAFE_FALL ? 0 : Double.POSITIVE_INFINITY;
      case OPEN, SOLID -> Double.POSITIVE_INFINITY;
    };
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
