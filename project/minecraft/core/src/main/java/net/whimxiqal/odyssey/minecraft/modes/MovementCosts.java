/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.modes;

/**
 * Default per-step cost constants, in <b>seconds</b> (Odyssey's universal cost unit). These are
 * rough vanilla speeds and will become configurable; for now they are tuned to be simple and
 * relative-correct (flying &lt; walking &lt; swimming, etc.).
 */
final class MovementCosts {

  /** Seconds to walk one block on flat ground. */
  static final double WALK = 0.20;

  /** Seconds to swim one block. */
  static final double SWIM = 0.30;

  /** Seconds to fly one block. */
  static final double FLY = 0.08;

  /** Seconds to climb one block (ladder/scaffolding/vine). */
  static final double CLIMB = 0.40;

  /** Seconds to travel one block by boat over water. */
  static final double BOAT = 0.15;

  /** Seconds to travel one block on horseback. */
  static final double HORSE = 0.12;

  /** Seconds to open a door and pass through it. */
  static final double OPEN_DOOR = 0.50;

  /** Seconds to place and enter a boat. */
  static final double PLACE_BOAT = 1.00;

  /** Seconds to fall one block. */
  static final double FALL_PER_BLOCK = 0.10;

  /** Multiplier applied to distance for a diagonal move. */
  static final double DIAGONAL = Math.sqrt(2.0);

  /** Blocks you can fall without taking damage. */
  static final int SAFE_FALL_BLOCKS = 3;

  /** Seconds of natural regeneration per half-heart of damage. */
  static final double HEAL_SECONDS_PER_HALF_HEART = 4.0;

  /** Multiplier turning heal time into a deterrent cost for taking damage. */
  static final double DAMAGE_COST_MULTIPLIER = 2.0;

  private MovementCosts() {}
}
