/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.api;

/**
 * What a route's hazards cost on top of the time it takes.
 *
 * <p>A search minimizes <b>cost</b>, and reports <b>time</b>. Cost is measured in seconds too: a
 * step costs the seconds it takes plus a penalty for anything that makes it worse than slow. Each
 * weight here says how many seconds of travel a hazard is worth avoiding, so a route that dodges it
 * by a detour shorter than that is taken. A penalty never shows in a step's time: the player is
 * told how long the route takes, not how unpleasant it is.
 *
 * <p>Time itself carries no weight: it is the unit. Doubling every weight is how to make hazards
 * matter twice as much.
 *
 * <p>The weights for harm — {@link #damagePerHalfHeart}, {@link #dangerousTerrain}, {@link
 * #dangerousEntity} and {@link #sculk} — are only charged to a player who can be hurt; see {@link
 * #harmless()}. The rest apply to everyone.
 *
 * @param damagePerHalfHeart seconds of travel worth avoiding a half-heart of damage, such as from a
 *     long fall or an ender pearl's landing
 * @param enderPearl seconds of travel worth keeping an ender pearl rather than throwing it
 * @param dangerousTerrain seconds added for each second spent beside dangerous terrain — lava,
 *     fire, magma, cactus, or a drop deep enough to hurt — at full strength, right next to it
 * @param dangerousTerrainRadius how many blocks away dangerous terrain is noticed; its weight falls
 *     off linearly to nothing past this distance. A wider radius reads more blocks every step
 * @param builtBlock seconds added for each block broken that is most likely someone's build, such
 *     as planks, glass or cobblestone
 * @param dangerousEntity seconds added for each second spent beside a hostile mob, such as a zombie
 *     or a creeper, where it stood when the search began, at full strength
 * @param dangerousEntityRadius how many blocks away a hostile mob is noticed; its weight falls off
 *     linearly to nothing past this distance
 * @param sculk seconds added for each second spent beside a sculk shrieker that can summon a
 *     warden, or a sculk sensor that can set one off, at full strength; noticed up to {@link
 *     #SCULK_RADIUS} blocks away
 */
public record CostWeights(
    double damagePerHalfHeart,
    double enderPearl,
    double dangerousTerrain,
    int dangerousTerrainRadius,
    double builtBlock,
    double dangerousEntity,
    int dangerousEntityRadius,
    double sculk) {

  /**
   * How many blocks away sculk is noticed: a sensor hears a step eight blocks off, and a shrieker
   * it sets off summons a warden.
   */
  public static final int SCULK_RADIUS = 8;

  /**
   * Rejects a negative weight: a penalty below zero would make a step cost less than it takes, and
   * every estimate the search steers by is a time.
   */
  public CostWeights {
    requireNonNegative("damagePerHalfHeart", damagePerHalfHeart);
    requireNonNegative("enderPearl", enderPearl);
    requireNonNegative("dangerousTerrain", dangerousTerrain);
    requireNonNegative("dangerousTerrainRadius", dangerousTerrainRadius);
    requireNonNegative("builtBlock", builtBlock);
    requireNonNegative("dangerousEntity", dangerousEntity);
    requireNonNegative("dangerousEntityRadius", dangerousEntityRadius);
    requireNonNegative("sculk", sculk);
  }

  /**
   * The default weights.
   *
   * <ul>
   *   <li>A half-heart is worth eight seconds, twice the four natural regeneration takes to heal
   *       it.
   *   <li>An ender pearl is worth five minutes, about what finding and killing an enderman for
   *       another takes.
   *   <li>A second beside danger costs one more, noticed only from the next block.
   *   <li>A built block is worth ten seconds of going round it.
   *   <li>A second beside a hostile mob costs five more, noticed up to sixteen blocks away: a mob
   *       moves, so its reach is wide and its weight gentle.
   *   <li>A second beside sculk costs fifty more: a warden is worth a long way round.
   * </ul>
   *
   * @return the default weights
   */
  public static CostWeights defaults() {
    return new CostWeights(8.0, 300.0, 1.0, 1, 10.0, 5.0, 16, 50.0);
  }

  /**
   * Returns these weights for a player who cannot be hurt: damage, danger, mobs and sculk cost
   * nothing, and what a pearl is worth stays as it is.
   *
   * @return the weights without harm
   */
  public CostWeights harmless() {
    return new CostWeights(
        0.0, enderPearl, 0.0, dangerousTerrainRadius, builtBlock, 0.0, dangerousEntityRadius, 0.0);
  }

  /**
   * Returns the cost of taking {@code halfHearts} of damage.
   *
   * @param halfHearts the damage taken, in half-hearts (health points)
   * @return the penalty, in seconds
   */
  public double damage(double halfHearts) {
    return damagePerHalfHeart * halfHearts;
  }

  private static void requireNonNegative(String name, double weight) {
    if (!(weight >= 0)) {
      throw new IllegalArgumentException(name + " must be >= 0: " + weight);
    }
  }
}
