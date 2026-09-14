/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import org.cobblestonemc.Domain;
import org.cobblestonemc.minecraft.MinecraftWorld.Environment;

/**
 * What one block of travel typically costs, per dimension — the number Tier-1 prices an unsolved
 * leg with, and the number the Tier-2 running-average heuristic starts from before it has walked
 * anywhere.
 *
 * <p><b>Typical, not cheapest.</b> A true lower bound (the cost of one block of flight, say) is
 * admissible and useless: every leg then comes back several times dearer than promised, which makes
 * some other unexplored route look cheaper, so Tier-1 re-plans and works through the alternatives
 * one costly Tier-2 solve at a time. The estimate that keeps Tier-1 stable is one that is roughly
 * <i>right</i>, which means folding in both the mode a player will usually be travelling by and how
 * much a real route winds compared with a straight line.
 *
 * <p><b>Per dimension, because a block of nether is not a block of overworld.</b> Nether terrain is
 * broken and layered, so the same straight-line distance costs far more to actually walk; the end
 * is close to open space. A single number across a route that crosses a portal prices one side of
 * it wrongly, and a route that crosses a portal is exactly the route where Tier-1 has a decision to
 * make.
 *
 * <p><b>These defaults are deliberate overestimates.</b> Overestimating makes a solved leg look
 * good against the unsolved alternatives, so Tier-1 tends to keep the route it first committed to
 * rather than re-planning around a promise it cannot keep. Tuning them down is a measurement
 * exercise on a real server: compare a solve's true cost against {@code distance × cost} for its
 * leg and adjust the dimension that is consistently off.
 *
 * <p>Per-world overrides (an admin's superflat or amplified world, which the dimension says nothing
 * about) are not supported yet; they belong here, resolved by world key ahead of environment.
 *
 * @param overworld seconds per block in the overworld
 * @param nether seconds per block in the nether
 * @param end seconds per block in the end
 * @param custom seconds per block in a dimension Cobblestone does not recognize
 */
public record AverageCostPerBlock(double overworld, double nether, double end, double custom) {

  /** Open ground with hills, water and the odd building to walk around. */
  public static final double DEFAULT_OVERWORLD = 0.5;

  /** Layered, broken terrain where a route winds much further than the straight line. */
  public static final double DEFAULT_NETHER = 1.0;

  /** The outer islands are effectively open space, so a route is nearly the straight line. */
  public static final double DEFAULT_END = 0.5;

  /** A modded or custom dimension; assumed overworld-like until an admin says otherwise. */
  public static final double DEFAULT_CUSTOM = DEFAULT_OVERWORLD;

  /**
   * Validates the costs.
   *
   * @throws IllegalArgumentException if any cost is not a positive, finite number of seconds
   */
  public AverageCostPerBlock {
    requirePositive(overworld, "overworld");
    requirePositive(nether, "nether");
    requirePositive(end, "end");
    requirePositive(custom, "custom");
  }

  /**
   * Returns the built-in costs.
   *
   * @return the defaults
   */
  public static AverageCostPerBlock defaults() {
    return new AverageCostPerBlock(DEFAULT_OVERWORLD, DEFAULT_NETHER, DEFAULT_END, DEFAULT_CUSTOM);
  }

  /**
   * Returns the cost per block in the given environment.
   *
   * @param environment the environment
   * @return seconds per block
   */
  public double forEnvironment(Environment environment) {
    return switch (environment) {
      case OVERWORLD -> overworld;
      case NETHER -> nether;
      case END -> end;
      case CUSTOM -> custom;
    };
  }

  /**
   * Returns the cost per block in the given domain, for the core heuristic — which speaks {@link
   * Domain} rather than {@link MinecraftWorld}. A domain that is not a Minecraft world cannot occur
   * in this embedder and is priced as overworld.
   *
   * @param domain the domain
   * @return seconds per block
   */
  public double forDomain(Domain domain) {
    return domain instanceof MinecraftWorld world ? forEnvironment(world.environment()) : overworld;
  }

  private static void requirePositive(double cost, String name) {
    if (!(cost > 0) || Double.isInfinite(cost)) { // also rejects NaN
      throw new IllegalArgumentException(
          name + " cost per block must be a positive, finite number of seconds: " + cost);
    }
  }
}
