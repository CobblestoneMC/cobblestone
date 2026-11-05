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
 * @param damagePerHalfHeart seconds of travel worth avoiding a half-heart of damage, such as from a
 *     long fall or an ender pearl's landing
 * @param enderPearl seconds of travel worth keeping an ender pearl rather than throwing it
 * @param turn seconds of travel worth not changing direction while moving with momentum: walking,
 *     jumping, swimming, flying, boating or riding. Steers towards straight routes
 */
public record CostWeights(double damagePerHalfHeart, double enderPearl, double turn) {

  /**
   * Rejects a negative weight: a penalty below zero would make a step cost less than it takes, and
   * every estimate the search steers by is a time.
   */
  public CostWeights {
    if (!(damagePerHalfHeart >= 0)) {
      throw new IllegalArgumentException("damagePerHalfHeart must be >= 0: " + damagePerHalfHeart);
    }
    if (!(enderPearl >= 0)) {
      throw new IllegalArgumentException("enderPearl must be >= 0: " + enderPearl);
    }
    if (!(turn >= 0)) {
      throw new IllegalArgumentException("turn must be >= 0: " + turn);
    }
  }

  /**
   * The default weights: a half-heart is worth eight seconds, twice the four seconds natural
   * regeneration takes to heal it, and an ender pearl five minutes, about what finding and killing
   * an enderman for another takes. A turn is worth a twentieth of a second, a quarter of a walked
   * block, which on the bench corpus cut turns by about a quarter for about an eighth more nodes.
   *
   * @return the default weights
   */
  public static CostWeights defaults() {
    return new CostWeights(8.0, 300.0, 0.05);
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
}
