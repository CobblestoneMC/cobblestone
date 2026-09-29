/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
  public static final double FALLBACK_COST_PER_BLOCK = 2 * Medium.MINEABLE.baseCostPerBlock();

  /** How coverage becomes a price. */
  public enum Blend {
    /**
     * Each medium claims its coverage share, cheapest first, and the shares are summed.
     *
     * <p>Reads as the careful choice and measures as a systematic over-estimate. Coverage is a
     * per-axis marginal, so it cannot tell whether the cheap slices line up along the route; this
     * rule assumes they do not, and charges the expensive medium for whatever the cheap one does
     * not cover. On terrain where a cheap medium is present but sparse -- a nether valley floor
     * with netherrack threading soul sand -- a real path weaves and stays cheap while this prices
     * it as if it could not.
     */
    SHARED,

    /**
     * The cheapest medium with any coverage prices the whole crossing.
     *
     * <p>The optimistic extreme: assumes the cheap slices always line up. Wrong in the direction A*
     * tolerates -- an estimate below the truth costs expansions, never correctness -- where {@link
     * #SHARED} is wrong in the direction that silently steers the search away from good routes.
     */
    CHEAPEST
  }

  /** How a diagonal's coverage is read from the axes it spans. */
  public enum Diagonal {
    /**
     * The least-covered axis decides.
     *
     * <p>The cautious reading: a medium that carries you east and another that carries you up do
     * not combine into one that carries you north-east-up, so the weakest component is the honest
     * summary of whether this medium makes the move at all.
     */
    WEAKEST,

    /**
     * The best-covered axis decides.
     *
     * <p>A diagonal is not really one move -- the fine search reaches the same cell by a short
     * staircase of axis-aligned steps, each of which only needs its own axis covered. Reading the
     * weakest axis prices a corner as though it had to be taken in one leap, which is what makes
     * the coarse path unable to cut corners the way a real one does.
     */
    STRONGEST
  }

  private final List<Medium> byCost;
  private final double[] costPerBlock;
  private final double fallbackCostPerBlock;
  private final Blend blend;
  private final Diagonal diagonal;

  private CoarseCost(
      List<Medium> byCost,
      double[] costPerBlock,
      double fallbackCostPerBlock,
      Blend blend,
      Diagonal diagonal) {
    this.byCost = byCost;
    this.costPerBlock = costPerBlock;
    this.fallbackCostPerBlock = fallbackCostPerBlock;
    this.blend = blend;
    this.diagonal = diagonal;
  }

  /**
   * Returns the coverage a medium has for a move spanning these axes.
   *
   * <p>The one place a diagonal differs from a straight crossing, so the rule lives here rather
   * than at each of the two call sites that used to spell it out.
   */
  private double coverage(Component component, Medium medium, Axis... axes) {
    double coverage = diagonal == Diagonal.WEAKEST ? 1.0 : 0.0;
    for (Axis axis : axes) {
      double axial = component.coverage(axis, medium);
      coverage =
          diagonal == Diagonal.WEAKEST ? Math.min(coverage, axial) : Math.max(coverage, axial);
    }
    return coverage;
  }

  /**
   * Returns this model reading diagonals a different way.
   *
   * @param value the rule to use
   * @return the model
   */
  public CoarseCost withDiagonal(Diagonal value) {
    return new CoarseCost(byCost, costPerBlock, fallbackCostPerBlock, blend, value);
  }

  /**
   * Returns this model with a different way of turning coverage into a price.
   *
   * @param value the rule to use
   * @return the model
   */
  public CoarseCost withBlend(Blend value) {
    return new CoarseCost(byCost, costPerBlock, fallbackCostPerBlock, value, diagonal);
  }

  /**
   * Creates a cost model for an agent with the given mediums.
   *
   * @param available what the agent can do
   * @return the cost model
   */
  public static CoarseCost forMediums(Set<Medium> available) {
    return forMediums(available, Map.of(), FALLBACK_COST_PER_BLOCK);
  }

  /**
   * Creates a cost model for an agent whose equipment changes what some terrain costs them.
   *
   * <p>The seam between a profile and a player. A profile says a section is {@link
   * Medium#SOUL_SAND}; what that is worth depends on whether the crossing player is wearing Soul
   * Speed boots, and only the caller knows. Mediums left out of {@code costs} keep {@link
   * Medium#baseCostPerBlock()}.
   *
   * @param available what the agent can cross
   * @param costs per-block costs for this agent, overriding the base where given
   * @param fallbackCostPerBlock what to charge for crossing that no medium covers
   * @return the cost model
   */
  public static CoarseCost forMediums(
      Set<Medium> available, Map<Medium, Double> costs, double fallbackCostPerBlock) {
    double[] perBlock = new double[Medium.COUNT];
    for (Medium medium : Medium.ALL) {
      Double override = costs.get(medium);
      perBlock[medium.ordinal()] =
          override == null ? medium.baseCostPerBlock() : Math.max(0, override);
    }
    // Sorted by what they cost *this* agent, which is what the blend below claims in order. A
    // sort on the base costs would have a Soul-Speed player claim soul sand after plain ground
    // when for them it is the cheaper of the two.
    List<Medium> sorted =
        available.stream()
            .sorted(java.util.Comparator.comparingDouble(m -> perBlock[m.ordinal()]))
            .toList();
    // CHEAPEST by default, from measurement rather than taste. Against optimal costs measured by
    // Dijkstra over eight scenarios, SHARED over-estimated on 45 of 88 sampled cells and by up to
    // 2.77x; CHEAPEST does so on 22, and by at most 1.35x. Over-estimating is the failure A* cannot
    // absorb -- it steers the search away from routes that are actually good, and says nothing
    // while doing it.
    return new CoarseCost(sorted, perBlock, fallbackCostPerBlock, Blend.CHEAPEST, Diagonal.WEAKEST);
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
    return forMediums(available, Map.of(), fallbackCostPerBlock);
  }

  /**
   * Returns the mediums a plain survival player has: everything but flight.
   *
   * @return the default medium set
   */
  public static Set<Medium> survival() {
    return EnumSet.of(
        Medium.WALKABLE, Medium.SOUL_SAND, Medium.SWIMMABLE, Medium.CLIMBABLE, Medium.MINEABLE);
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
    if (blend == Blend.CHEAPEST) {
      for (Medium medium : byCost) {
        if (coverage(component, medium, axes) > 0) {
          return blocks * costPerBlock[medium.ordinal()];
        }
      }
      return blocks * fallbackCostPerBlock;
    }
    double remaining = 1.0;
    double perBlock = 0.0;
    for (Medium medium : byCost) {
      if (remaining <= 0) {
        break;
      }
      double coverage = coverage(component, medium, axes);
      // Cheapest-first claiming is what makes overlapping coverage behave. Flight can occupy every
      // passable cell, so it overlaps everything; claiming greedily means a flier simply prices the
      // whole crossing at flight rate and a walker's flight coverage never enters the sum.
      double share = Math.min(coverage, remaining);
      perBlock += share * costPerBlock[medium.ordinal()];
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
    return byCost.isEmpty() ? fallbackCostPerBlock : costPerBlock[byCost.get(0).ordinal()];
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
