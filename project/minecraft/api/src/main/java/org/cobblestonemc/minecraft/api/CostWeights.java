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
 * <p>The weights for harm — {@link #damagePerHalfHeart} and {@link #dangerousTerrain} — are only
 * charged to a player who can be hurt; see {@link #harmless()}. The rest apply to everyone.
 *
 * @param damagePerHalfHeart seconds of travel worth avoiding a half-heart of damage, such as from a
 *     long fall, an ender pearl's landing or drowning
 * @param dangerousTerrain seconds added for each second spent beside dangerous terrain — lava,
 *     fire, magma, cactus, or a drop deep enough to hurt — at full strength, right next to it
 * @param dangerousTerrainRadius how many blocks away dangerous terrain is noticed; its weight falls
 *     off linearly to nothing past this distance. A wider radius reads more blocks every step
 * @param builtBlock seconds added for each block broken that is most likely someone's build, such
 *     as planks, glass or cobblestone
 * @param crampedGliding seconds added for each second spent gliding on an elytra under cover or
 *     close to a wall, where it is hard to steer; open sky is free
 * @param coast seconds added for each second spent boating right beside the shore, where a boat
 *     catches on the land
 */
public record CostWeights(
    double damagePerHalfHeart,
    double dangerousTerrain,
    int dangerousTerrainRadius,
    double builtBlock,
    double crampedGliding,
    double coast) {

  /**
   * Rejects a negative weight: a penalty below zero would make a step cost less than it takes, and
   * every estimate the search steers by is a time.
   */
  public CostWeights {
    requireNonNegative("damagePerHalfHeart", damagePerHalfHeart);
    requireNonNegative("dangerousTerrain", dangerousTerrain);
    requireNonNegative("dangerousTerrainRadius", dangerousTerrainRadius);
    requireNonNegative("builtBlock", builtBlock);
    requireNonNegative("crampedGliding", crampedGliding);
    requireNonNegative("coast", coast);
  }

  /**
   * The default weights.
   *
   * <ul>
   *   <li>A half-heart is worth eight seconds, twice the four natural regeneration takes to heal
   *       it.
   *   <li>A second beside danger costs two more, noticed up to two blocks away.
   *   <li>A built block is worth ten seconds of going round it.
   *   <li>A second of cramped gliding costs ten more: covering ground slower than walking, so a
   *       glider climbs into open sky rather than threading through trees.
   *   <li>A second boating beside the shore costs one more, which keeps a boat a block or two out.
   * </ul>
   *
   * @return the default weights
   */
  public static CostWeights defaults() {
    return new CostWeights(8.0, 2.0, 2, 10.0, 10.0, 1.0);
  }

  /**
   * Returns these weights for a player who cannot be hurt: damage and danger cost nothing.
   *
   * @return the weights without harm
   */
  public CostWeights harmless() {
    return new CostWeights(0.0, 0.0, dangerousTerrainRadius, builtBlock, crampedGliding, coast);
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
