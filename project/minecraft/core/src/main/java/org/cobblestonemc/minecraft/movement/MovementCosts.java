/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.movement;

/**
 * Default per-step times, in <b>seconds</b>. These are rough vanilla speeds, tuned to be simple and
 * relative-correct (flying &lt; walking &lt; swimming, etc.). They are what a step takes; what it
 * costs on top, for harm done along the way, is priced by {@code CostWeights}.
 */
final class MovementCosts {

  /** Seconds to walk one block on flat ground. */
  static final double WALK = 0.20;

  /** Seconds to swim one block. */
  static final double SWIM = 0.30;

  /** Seconds to fly one block. */
  static final double FLY = 0.08;

  /** Seconds a glider boosted by a rocket takes for a block: about twenty blocks a second. */
  static final double BOOST = 0.05;

  /** Seconds a glider takes for a block gliding down without a boost. */
  static final double GLIDE_DOWN = 0.10;

  /**
   * Seconds a glider takes for a block coasting level without a boost: slow and careful, as through
   * a one-block hole such as an end gateway.
   */
  static final double GLIDE_LEVEL = 1.0;

  /** Blocks a rocket fired from the ground carries a glider, for each level of Flight Duration. */
  static final int TAKEOFF_BLOCKS_PER_DURATION = 40;

  /**
   * Seconds added to a takeoff from the surface of water: a swimmer has to leap clear before the
   * elytra opens and the rocket fires, which takes a try or two.
   */
  static final double WATER_TAKEOFF = 1.0;

  /** Seconds to climb one block (ladder/scaffolding/vine). */
  static final double CLIMB = 0.40;

  /** Seconds to travel one block by boat over water. */
  static final double BOAT = 0.15;

  /**
   * How much longer a boat takes right beside the shore: half as long again, falling off to nothing
   * past {@link Surroundings#COAST_RADIUS}. Scraping along the bank and catching on corners slows
   * it down; it is not a danger, so it is time rather than a penalty. It keeps a boat a block or
   * two out, where it is free.
   */
  static final double COAST_SLOWDOWN = 0.5;

  /** Seconds to travel one block on horseback. */
  static final double HORSE = 0.12;

  /**
   * Seconds a jump up a full block takes on top of the step: a sprint cannot carry through it, so
   * the player slows, waits out the jump's air time and builds speed again. A slab or stair is
   * stepped up without one. Half a block's walk, so a hop has to save half a block to be worth
   * taking. The region heuristic does not know about it, so raising it makes searches expand much
   * more.
   */
  static final double JUMP_UP = 0.10;

  /** Seconds to open a door and pass through it. */
  static final double OPEN_DOOR = 0.50;

  /** Seconds to place and enter a boat. */
  static final double PLACE_BOAT = 1.00;

  /** Seconds to fall one block. */
  static final double FALL_PER_BLOCK = 0.18;

  /** Multiplier applied to distance for a diagonal move. */
  static final double DIAGONAL = Math.sqrt(2.0);

  /**
   * How much faster Soul Speed boots of a level walk on soul sand and soul soil: the enchantment
   * adds {@code 0.03 * (1 + 0.35 * level)} to a base movement speed of 0.1, and lifts soul sand's
   * drag. Mirrored by {@code MinecraftAgent}.
   *
   * @param level the Soul Speed level, at least 1
   * @return the factor walking speed is multiplied by
   */
  static double soulSpeedFactor(int level) {
    return 1 + 0.3 * (1 + 0.35 * level);
  }

  /** Blocks you can fall without taking damage. */
  static final int SAFE_FALL_BLOCKS = 3;

  /**
   * Seconds to swim one block across in Depth Strider boots of a level: each level, up to three,
   * takes a third of the gap between swimming and walking away. Mirrored by {@code MinecraftAgent}.
   *
   * @param level the Depth Strider level, 0 without it
   * @return the seconds to swim a block
   */
  static double swimAcross(int level) {
    return SWIM - (SWIM - WALK) * Math.min(level, 3) / 3.0;
  }

  private MovementCosts() {}
}
