/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

/**
 * Something an agent can do to cross terrain, and roughly what a block of it costs.
 *
 * <p><b>A medium is not a mode.</b> A mode generates individual steps with exact costs and knows
 * about corner-cutting, headroom and doorways; a medium is the one-number answer to "roughly what
 * does a block of travel cost this way". The fine search needs the first. The region layer needs
 * the second, because it prices a whole region as a straight line and any per-step precision would
 * be averaged away immediately.
 *
 * <p><b>A medium is a capability, and a {@link RegionClass} is terrain.</b> An agent's set of
 * mediums says what it can do; {@link RegionCost} matches them against the class of each region to
 * decide what crossing it costs and whether it is possible at all. Keeping the two apart is what
 * lets one graph of the terrain serve every agent.
 *
 * <p>Soul sand is its own medium rather than part of walking because the agent changes the answer:
 * it is roughly {@code 1 / 0.4} the cost of ordinary ground on foot, and <i>cheaper</i> than ground
 * for a player in Soul Speed boots. One number could never be right for both.
 *
 * <p><b>The base costs mirror {@code MovementCosts}.</b> They are what the fine search actually
 * charges for a block of that terrain, not independent guesses, because any gap between the two
 * becomes a flat bias in every estimate. They had drifted once: walkable was 0.215 against the
 * search's 0.20 and swimmable 0.45 against 0.30, which over-priced dry ground by 7.5% and water by
 * 50% everywhere at once.
 *
 * <p>⚠️ Keep them reconciled. A change to {@code MovementCosts} that is not mirrored here reappears
 * as an estimate that is quietly wrong on every route the medium touches, and {@code bench verify}
 * is the only thing that would notice.
 */
public enum Medium {

  /** Walking, jumping and stepping up, on solid ground. */
  WALKABLE(0.20),

  /** Walking on soul sand and soul soil. */
  SOUL_SAND(0.50),

  /** Swimming. */
  SWIMMABLE(0.30),

  /** Boating across a water surface. */
  BOATABLE(0.15),

  /** Climbing ladders, vines and scaffolding. */
  CLIMBABLE(0.4),

  /** Flying through open space. */
  FLYABLE(0.08),

  /**
   * Digging through breakable material.
   *
   * <p>Its cost is not this number: {@link RegionCost} prices digging from the region's own break
   * time and the direction of travel, as {@code MineMode} does. This is only a nominal rate.
   */
  MINEABLE(1.4);

  private final double baseCostPerBlock;

  Medium(double baseCostPerBlock) {
    this.baseCostPerBlock = baseCostPerBlock;
  }

  /**
   * Returns the approximate seconds to travel one block this way, before the agent is considered.
   *
   * <p>A default, not a fact. {@link RegionCost} may be given a different number for an agent whose
   * equipment or abilities change what this costs them.
   *
   * @return the base cost per block
   */
  public double baseCostPerBlock() {
    return baseCostPerBlock;
  }
}
