/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.cobblestonemc.minecraft.lod.SectionProfile.Axis;
import org.cobblestonemc.minecraft.lod.SectionProfile.Component;

/**
 * Turns a terrain profile into a cost, for one agent.
 *
 * <p>This is the only place the agent enters the profile layer. Profiles describe terrain and
 * nothing else, which is what lets one cached pyramid serve every player on a server; what a
 * crossing costs depends on what the crossing body can do, and that is decided here, per query.
 *
 * <p><b>The blend.</b> Take the mediums the agent has, cheapest first, and let each claim as much
 * of the crossing as its coverage allows. Whatever is left over is not impassable — it is priced at
 * a deliberately high but finite fallback, so that a sparsely-covered section looks expensive
 * rather than walled off.
 *
 * <p>⚠️ The fallback is doing real work and is the tuning risk in this whole layer. Too low and
 * every section looks crossable, so the estimate flattens towards straight-line distance and the
 * coarse tier stops informing anything. Too high and thinly-covered terrain reads as a wall, the
 * estimate turns pessimistic, and the fine search is pushed away from routes that are in fact fine
 * — which costs path quality, the one thing the optimism was protecting.
 */
public final class CoarseCost {

  /**
   * What a block of the crossing costs where no available medium reaches.
   *
   * <p>Twice mining, which is the most expensive thing an agent can actually do. Finite on purpose:
   * infinity here would turn an imperfect profile into a fence, and the whole design rests on the
   * coarse layer producing a heuristic that can be wrong without making a solvable route
   * unreachable.
   */
  public static final double FALLBACK_COST_PER_BLOCK = 2 * Medium.MINE.costPerBlock();

  private final List<Medium> byCost;
  private final double fallbackCostPerBlock;

  private CoarseCost(List<Medium> byCost, double fallbackCostPerBlock) {
    this.byCost = byCost;
    this.fallbackCostPerBlock = fallbackCostPerBlock;
  }

  /**
   * Creates a cost model for an agent with the given mediums.
   *
   * @param available what the agent can do
   * @return the cost model
   */
  public static CoarseCost forMediums(Set<Medium> available) {
    return forMediums(available, FALLBACK_COST_PER_BLOCK);
  }

  /**
   * Creates a cost model with a chosen fallback rate.
   *
   * <p>Separate from the default because this number is the layer's dominant tuning knob and is
   * meant to be swept against measured accuracy rather than argued about. See {@link
   * #FALLBACK_COST_PER_BLOCK}.
   *
   * @param available what the agent can do
   * @param fallbackCostPerBlock what to charge for crossing that no medium covers
   * @return the cost model
   */
  public static CoarseCost forMediums(Set<Medium> available, double fallbackCostPerBlock) {
    List<Medium> sorted =
        available.stream()
            .sorted(java.util.Comparator.comparingDouble(Medium::costPerBlock))
            .toList();
    return new CoarseCost(sorted, fallbackCostPerBlock);
  }

  /**
   * Returns the mediums a plain survival player has: everything but flight.
   *
   * @return the default medium set
   */
  public static Set<Medium> survival() {
    return EnumSet.of(Medium.WALK, Medium.SWIM, Medium.CLIMB, Medium.MINE);
  }

  /**
   * Returns the seconds to cross {@code blocks} of the given component along an axis.
   *
   * @param component the component being crossed
   * @param axis the axis of travel
   * @param blocks how far, in blocks
   * @return the cost in seconds
   */
  public double crossingCost(Component component, Axis axis, double blocks) {
    return crossingCost(component, blocks, axis);
  }

  /**
   * Returns the seconds to cross {@code blocks} of a component along a direction spanning the given
   * axes.
   *
   * <p>For a diagonal move the coverage taken is the <b>minimum</b> across the axes involved. A
   * medium that carries you east and a different one that carries you up do not combine into one
   * that carries you north-east-up; the honest summary of "can this medium make this diagonal" is
   * the weakest of its components.
   *
   * @param component the component being crossed
   * @param blocks how far, in blocks
   * @param axes the axes the direction spans, one to three of them
   * @return the cost in seconds
   */
  public double crossingCost(Component component, double blocks, Axis... axes) {
    double remaining = 1.0;
    double perBlock = 0.0;
    for (Medium medium : byCost) {
      if (remaining <= 0) {
        break;
      }
      double coverage = 1.0;
      for (Axis axis : axes) {
        coverage = Math.min(coverage, component.coverage(axis, medium));
      }
      // Cheapest-first claiming is what makes overlapping coverage behave. Flight can occupy every
      // passable cell, so it overlaps everything; claiming greedily means a flier simply prices the
      // whole crossing at flight rate and a walker's flight coverage never enters the sum.
      double share = Math.min(coverage, remaining);
      perBlock += share * medium.costPerBlock();
      remaining -= share;
    }
    perBlock += remaining * fallbackCostPerBlock;
    return blocks * perBlock;
  }

  /**
   * Returns the cheapest per-block rate any of the agent's mediums could manage anywhere.
   *
   * <p>The admissible floor: used to price terrain that has not been profiled, where guessing
   * anything higher risks turning an unexplored region into a wall.
   *
   * @return the cheapest cost per block
   */
  public double cheapestCostPerBlock() {
    return byCost.isEmpty() ? fallbackCostPerBlock : byCost.get(0).costPerBlock();
  }

  /**
   * Returns the mediums this model was built for, cheapest first.
   *
   * @return the mediums
   */
  public List<Medium> mediums() {
    return byCost;
  }
}
