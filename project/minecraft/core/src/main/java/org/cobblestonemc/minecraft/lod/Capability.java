/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

/**
 * Something an agent can do, and what a block of it costs on terrain that does not change it.
 *
 * <p><b>Capabilities are the agent; {@link RegionClass}es are the terrain.</b> An agent is the set
 * of capabilities it has. A region class says which capabilities cross it and how the terrain
 * scales each one: soul sand slows walking but not Soul Speed, and only a boat cares that water has
 * a surface. {@link RegionCost} crosses each region by the best capability the agent has for it.
 * One graph of the terrain serves every agent because the agent only enters at that last step.
 *
 * <p><b>A capability is not a mode.</b> A mode generates individual steps with exact costs and
 * knows about corner-cutting, headroom and doorways. A capability is the one-number answer to
 * "roughly what does a block of travel cost this way". The fine search needs the first. The region
 * layer needs the second, because it prices a whole region as a straight line and any per-step
 * precision would be averaged away immediately.
 *
 * <p><b>The base costs mirror {@code MovementCosts}.</b> They are what the fine search actually
 * charges for a block of that terrain, not independent guesses, because any gap between the two
 * becomes a flat bias in every estimate. They had drifted once: walking was 0.215 against the
 * search's 0.20 and swimming 0.45 against 0.30, which over-priced dry ground by 7.5% and water by
 * 50% everywhere at once.
 *
 * <p>⚠️ Keep them reconciled. A change to {@code MovementCosts} that is not mirrored here reappears
 * as an estimate that is quietly wrong on every route the capability touches, and {@code bench
 * verify} is the only thing that would notice.
 */
public enum Capability {

  /** Walking, jumping and stepping up, on solid ground. */
  WALK(0.20),

  /**
   * Walking in Soul Speed boots, on the blocks they work on.
   *
   * <p>The base cost is the first level's, {@code MovementCosts.soulSpeedFactor(1)}; a player with
   * a higher level is given their own rate by {@link RegionCost#forPlayer}.
   */
  SOUL_SPEED(0.20 / 1.405),

  /** Swimming. */
  SWIM(0.30),

  /** Boating across a water surface. */
  BOAT(0.15),

  /** Climbing ladders, vines and scaffolding. */
  CLIMB(0.4),

  /** Flying through open space. */
  FLY(0.08),

  /**
   * Digging through breakable material.
   *
   * <p>Its cost is not this number: {@link RegionCost} prices digging from the region's own break
   * time and the direction of travel, as {@code MineMode} does. This is only a nominal rate.
   */
  MINE(1.4);

  /** Every capability, cached; {@code values()} allocates. */
  public static final Capability[] ALL = values();

  private final double baseCostPerBlock;

  Capability(double baseCostPerBlock) {
    this.baseCostPerBlock = baseCostPerBlock;
  }

  /**
   * Returns the approximate seconds to travel one block this way, on terrain that does not change
   * it, before the agent is considered.
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
