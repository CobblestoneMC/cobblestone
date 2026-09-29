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
 * <p><b>A medium names terrain, not an action.</b> {@code WALKABLE} is a property of the ground --
 * something a profile can record by looking at blocks -- while what it costs to cross depends on
 * who is crossing. That is why the cost here is only a <i>base</i>: {@link CoarseCost} is where an
 * agent turns it into a number, and two players can price the same profile differently. Keeping the
 * name a property of the world is what lets one cached profile serve every player.
 *
 * <p>It also means one mode can span several mediums. Walking over soul sand and walking over stone
 * are the same {@code WALK} mode, but they are different terrain with different costs, and only one
 * of them gets faster when the player is wearing Soul Speed boots -- so they are different mediums.
 *
 * <p>⚠️ <b>Adding or reordering a medium invalidates every stored profile.</b> Coverage is held as
 * a dense array indexed by {@link #ordinal()} and sized by {@link #COUNT}, so an old profile read
 * against a new enum is silently misinterpreted rather than rejected. Bump {@link
 * SectionProfile#FORMAT_VERSION} in the same change, and anything caching profiles must discard
 * what it holds when that number moves.
 *
 * <p>The per-block costs here are deliberately approximate and are only ever used to turn a profile
 * into an estimate. The real costs live in the modes, and the estimate's job is to be comparable
 * across terrain rather than exact anywhere.
 */
public enum Medium {

  /** Solid ground with headroom: what walking, jumping and stepping up all cross. */
  WALKABLE(0.215),

  /**
   * Solid ground that drags: soul sand and soul soil.
   *
   * <p>Its own medium rather than part of {@link #WALKABLE} because the agent changes the answer.
   * Soul sand is roughly {@code 1 / 0.4} the cost of ordinary ground on foot, and <i>cheaper</i>
   * than ordinary ground for a player in Soul Speed boots. One number could never be right for
   * both, and averaging it into walkable ground made the coarse estimate over-price whole nether
   * biomes.
   */
  SOUL_SAND(0.54),

  /** Water, crossed by swimming. */
  SWIMMABLE(0.45),

  /** A water surface a boat floats on. */
  BOATABLE(0.12),

  /** Ladders, vines and scaffolding. */
  CLIMBABLE(0.4),

  /** Open space, crossed by flying. */
  FLYABLE(0.09),

  /** Breakable material. Expensive, and almost everywhere. */
  MINEABLE(1.4);

  /** Every medium, cached; {@code values()} allocates and this is read per cell. */
  public static final Medium[] ALL = values();

  /** How many there are, for sizing coverage arrays. */
  public static final int COUNT = ALL.length;

  private final double baseCostPerBlock;

  Medium(double baseCostPerBlock) {
    this.baseCostPerBlock = baseCostPerBlock;
  }

  /**
   * Returns the approximate seconds to travel one block of this terrain, before the agent is
   * considered.
   *
   * <p>A default, not a fact. {@link CoarseCost} may be given a different number for an agent whose
   * equipment or abilities change what this terrain costs them.
   *
   * @return the base cost per block
   */
  public double baseCostPerBlock() {
    return baseCostPerBlock;
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
      // Both are decided from the block below and the headroom above, which only the profiler can
      // see, so they decline to answer from a cell alone.
      case WALKABLE, SOUL_SAND -> false;
      case SWIMMABLE -> block.isWater();
      // A navigable surface is water with something other than water above it; the profiler adds
      // that condition, so here it is only "is this water at all".
      case BOATABLE -> block.isWater();
      case CLIMBABLE -> block.isClimbable() || block.isScaffolding();
      case FLYABLE -> block.isPassable();
      // Bedrock and the like are unbreakable, and a cube of them must not report as diggable.
      case MINEABLE -> !Double.isInfinite(block.breakTimeSeconds());
    };
  }
}
