/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import org.cobblestonemc.minecraft.MinecraftBlock;

/**
 * A coarse cost-regime of travel, as the profile layer reasons about it.
 *
 * <p><b>A medium is not a mode.</b> A mode generates individual steps with exact costs and knows
 * about corner-cutting, headroom and doorways; a medium is the one-number answer to "roughly what
 * does a block of travel cost around here". Tier 3 needs the first. The profile layer needs the
 * second, because it is summarizing 4 096 blocks at a time and any per-step precision it computed
 * would be averaged away immediately.
 *
 * <p>The per-block costs here are deliberately approximate and are only ever used to turn a profile
 * into an estimate. The real costs live in the modes, and the estimate's job is to be comparable
 * across terrain rather than exact anywhere.
 */
public enum Medium {

  /** Walking, jumping and stepping up: the default way across solid ground. */
  WALK(0.215),

  /** Swimming through water. */
  SWIM(0.45),

  /** Travelling on a navigable water surface in a boat. */
  BOAT(0.12),

  /** Ladders, vines and scaffolding. */
  CLIMB(0.4),

  /** Flight, where the agent has it. */
  FLY(0.09),

  /** Breaking through. Expensive, and available almost everywhere. */
  MINE(1.4);

  /** Every medium, cached; {@code values()} allocates and this is read per cell. */
  public static final Medium[] ALL = values();

  /** How many there are, for sizing coverage arrays. */
  public static final int COUNT = ALL.length;

  private final double costPerBlock;

  Medium(double costPerBlock) {
    this.costPerBlock = costPerBlock;
  }

  /**
   * Returns the approximate seconds to travel one block in this medium.
   *
   * @return the cost per block
   */
  public double costPerBlock() {
    return costPerBlock;
  }

  /**
   * Returns whether a body using this medium can occupy the given block.
   *
   * <p>The one place the profile layer looks at a block, so the whole definition of each medium is
   * here rather than spread across the profiler.
   *
   * <p>Deliberately local: whether a cell is <em>walkable</em> depends on the block below and the
   * headroom above, which the profiler supplies, while everything else is a property of the cell
   * itself.
   *
   * @param block the block
   * @return {@code true} if this medium can occupy it
   */
  public boolean occupies(MinecraftBlock block) {
    return switch (this) {
      // Handled by the profiler, which can see the neighbours this needs.
      case WALK -> false;
      case SWIM -> block.isWater();
      // A navigable surface is water with something other than water above it; the profiler adds
      // that condition, so here it is only "is this water at all".
      case BOAT -> block.isWater();
      case CLIMB -> block.isClimbable() || block.isScaffolding();
      case FLY -> block.isPassable();
      // Bedrock and the like are unbreakable, and a cube of them must not report as diggable.
      case MINE -> !Double.isInfinite(block.breakTimeSeconds());
    };
  }
}
