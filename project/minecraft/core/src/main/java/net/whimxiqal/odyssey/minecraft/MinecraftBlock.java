/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

/**
 * An immutable snapshot of the <b>local</b> facts about one block that modes need — no neighbor
 * awareness. Contextual decisions (corner-cutting, "vine needs a wall", door activators) are made
 * by modes composing several {@code MinecraftBlock} lookups.
 *
 * <p>Many methods have sensible defaults so a platform implementation only overrides what differs
 * from a plain solid block. Facts that depend on the live block state (passability, door open/closed,
 * break time) are computed by the platform; material-level traits (danger, speed factor, climbable,
 * boat support) may come from a shared trait table.
 */
public interface MinecraftBlock {

  /**
   * Returns the vanilla namespaced id of this block (e.g. {@code "minecraft:soul_sand"}), or a
   * platform-specific id for modded blocks.
   *
   * @return the namespaced type id
   */
  String typeKey();

  /**
   * Returns whether a body can freely occupy this block (air, tall grass, …). Water/lava are not
   * "passable" in this sense (handled by swim/danger); doors and partial blocks are not passable to
   * general modes (handled by specialist modes / step-up).
   *
   * @return {@code true} if freely occupiable
   */
  boolean isPassable();

  /**
   * Returns whether this block can be stood on top of (a solid or partial top face).
   *
   * @return {@code true} if it provides footing
   */
  boolean isSolidTop();

  /**
   * Returns whether the block's top sits at roughly half height (slab, snow layer, …), making it a
   * free step-up target.
   *
   * @return {@code true} for half-height footing
   */
  default boolean isHalfHeight() {
    return false;
  }

  /**
   * Returns whether the body can enter this block from the given side (for partial blocks like
   * carpets, trapdoors, and closed doors). Defaults to {@link #isPassable()}.
   *
   * @param from the side entered from
   * @return {@code true} if enterable from that side
   */
  default boolean isEnterable(Direction from) {
    return isPassable();
  }

  /**
   * Returns whether the body can exit this block toward the given side. Defaults to
   * {@link #isPassable()}.
   *
   * @param to the side exited toward
   * @return {@code true} if exitable toward that side
   */
  default boolean isExitable(Direction to) {
    return isPassable();
  }

  /** whether this block is water. */
  boolean isWater();

  /** whether this block is lava. */
  default boolean isLava() {
    return false;
  }

  /** whether this block is climbable (ladder, vine, scaffolding). */
  default boolean isClimbable() {
    return false;
  }

  /** whether this block is scaffolding (climbable and exitable sideways at any level). */
  default boolean isScaffolding() {
    return false;
  }

  /** whether occupying/contacting this block harms the agent (lava, fire, cactus, …). */
  default boolean isDangerous() {
    return isLava();
  }

  /** the damage per second inflicted while in contact with this block. */
  default double damagePerSecond() {
    return isLava() ? 20.0 : 0.0;
  }

  /**
   * Returns the time in seconds to break this block with the assumed (stone) tool, or
   * {@link Double#POSITIVE_INFINITY} if unbreakable.
   *
   * @return the break time in seconds
   */
  default double breakTimeSeconds() {
    return Double.POSITIVE_INFINITY;
  }

  /** whether a boat can ride on top of this block (water, and ice for speed). */
  default boolean supportsBoat() {
    return isWater();
  }

  /**
   * Returns a multiplier on movement speed through/over this block (1.0 normal, {@code <1} for soul
   * sand/honey/cobweb, {@code >1} for ice). Cost is divided by this factor.
   *
   * @return the speed factor
   */
  default double speedFactor() {
    return 1.0;
  }

  /** whether this is an openable barrier: door, fence gate, or trapdoor. */
  default boolean isDoor() {
    return false;
  }

  /** whether the door/gate/trapdoor is currently open. */
  default boolean isOpen() {
    return false;
  }

  /** whether this door can be opened by hand (wooden), as opposed to needing redstone. */
  default boolean opensByHand() {
    return false;
  }

  /** whether this block is a pressure plate (can activate an adjacent iron door). */
  default boolean isPressurePlate() {
    return false;
  }
}
