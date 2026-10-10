/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

/**
 * Everything a search needs to know about one block state, as a captured fact.
 *
 * <p><b>Deliberately not a {@code MinecraftBlock}.</b> This module knows nothing about Minecraft,
 * so both ends adapt: the copier turns the platform's real block implementation into one of these,
 * and the benchmark platform turns one of these back into a block. Keeping the middle neutral is
 * what lets a capture be read without a server anywhere on the classpath.
 *
 * <p>Traits live beside block data rather than inside it (see {@link Sbc}) because their lifetimes
 * differ: captured blocks are frozen forever, while trait logic is code that changes. Regenerating
 * a trait table never touches a single column file.
 *
 * <p>The boolean traits are packed into {@link #bits} so that a table of a few hundred states costs
 * a few kilobytes and a lookup costs a mask. The direction-dependent traits — whether a body may
 * enter or leave a block from a given side, which partial blocks like carpets and closed doors
 * answer differently per face — occupy two six-bit fields indexed by {@link #DIRECTIONS}.
 *
 * @param bits the packed boolean and directional traits
 * @param breakTimeSeconds seconds to break with the assumed tool, or {@link
 *     Double#POSITIVE_INFINITY} if unbreakable
 * @param speedFactor multiplier on movement speed through or over the block
 * @param damagePerSecond damage taken per second while in contact
 */
public record BlockTraits(
    long bits, double breakTimeSeconds, double speedFactor, double damagePerSecond) {

  /**
   * The direction order the {@code enterable} and {@code exitable} bit fields use.
   *
   * <p>Written down here, in this module, because it is a <b>wire contract</b>: the copier encodes
   * against it on one machine and the platform decodes against it on another, months later. Neither
   * side may use its own enum's ordinal, which would silently reinterpret every face the day
   * someone reorders a constant.
   */
  public static final String[] DIRECTIONS = {"NORTH", "EAST", "SOUTH", "WEST", "UP", "DOWN"};

  /** Bit index: a body can freely occupy this block. */
  public static final int PASSABLE = 0;

  /** Bit index: this block can be stood on top of. */
  public static final int SOLID_TOP = 1;

  /** Bit index: the block's top sits at roughly half height. */
  public static final int HALF_HEIGHT = 2;

  /** Bit index: this block is water. */
  public static final int WATER = 3;

  /** Bit index: this block is lava. */
  public static final int LAVA = 4;

  /** Bit index: this block is climbable. */
  public static final int CLIMBABLE = 5;

  /** Bit index: this block is scaffolding. */
  public static final int SCAFFOLDING = 6;

  /** Bit index: contacting this block harms the agent. */
  public static final int DANGEROUS = 7;

  /** Bit index: a boat can ride on top of this block. */
  public static final int SUPPORTS_BOAT = 8;

  /** Bit index: this is a door, fence gate, or trapdoor. */
  public static final int DOOR = 9;

  /** Bit index: this openable barrier is a trapdoor. */
  public static final int TRAPDOOR = 10;

  /** Bit index: this door, gate, or trapdoor is currently open. */
  public static final int OPEN = 11;

  /** Bit index: this door can be opened by hand. */
  public static final int OPENS_BY_HAND = 12;

  /** Bit index: this block is a pressure plate. */
  public static final int PRESSURE_PLATE = 13;

  /** Bit index of the first of six {@code enterable} bits, one per {@link #DIRECTIONS} entry. */
  public static final int ENTERABLE = 16;

  /** Bit index of the first of six {@code exitable} bits, one per {@link #DIRECTIONS} entry. */
  public static final int EXITABLE = 24;

  /**
   * Returns whether the given boolean trait is set.
   *
   * @param bitIndex one of the bit-index constants on this type
   * @return {@code true} if set
   */
  public boolean has(int bitIndex) {
    return (bits & (1L << bitIndex)) != 0;
  }

  /**
   * Returns whether a body may enter this block from the given side.
   *
   * @param directionIndex the index within {@link #DIRECTIONS}
   * @return {@code true} if enterable from that side
   */
  public boolean enterableFrom(int directionIndex) {
    return (bits & (1L << (ENTERABLE + directionIndex))) != 0;
  }

  /**
   * Returns whether a body may leave this block toward the given side.
   *
   * @param directionIndex the index within {@link #DIRECTIONS}
   * @return {@code true} if exitable toward that side
   */
  public boolean exitableToward(int directionIndex) {
    return (bits & (1L << (EXITABLE + directionIndex))) != 0;
  }

  /**
   * Creates a builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Accumulates trait bits without callers doing their own shifting. */
  public static final class Builder {

    private long bits;
    private double breakTimeSeconds = Double.POSITIVE_INFINITY;
    private double speedFactor = 1.0;
    private double damagePerSecond;

    private Builder() {}

    /**
     * Sets or clears a boolean trait.
     *
     * @param bitIndex one of the bit-index constants
     * @param value the value
     * @return this builder
     */
    public Builder set(int bitIndex, boolean value) {
      if (value) {
        bits |= 1L << bitIndex;
      } else {
        bits &= ~(1L << bitIndex);
      }
      return this;
    }

    /**
     * Sets whether the block is enterable from the given side.
     *
     * @param directionIndex the index within {@link #DIRECTIONS}
     * @param value the value
     * @return this builder
     */
    public Builder enterable(int directionIndex, boolean value) {
      return set(ENTERABLE + directionIndex, value);
    }

    /**
     * Sets whether the block is exitable toward the given side.
     *
     * @param directionIndex the index within {@link #DIRECTIONS}
     * @param value the value
     * @return this builder
     */
    public Builder exitable(int directionIndex, boolean value) {
      return set(EXITABLE + directionIndex, value);
    }

    /**
     * Sets the break time in seconds.
     *
     * @param seconds the break time, or {@link Double#POSITIVE_INFINITY}
     * @return this builder
     */
    public Builder breakTimeSeconds(double seconds) {
      this.breakTimeSeconds = seconds;
      return this;
    }

    /**
     * Sets the speed factor.
     *
     * @param factor the multiplier
     * @return this builder
     */
    public Builder speedFactor(double factor) {
      this.speedFactor = factor;
      return this;
    }

    /**
     * Sets the contact damage per second.
     *
     * @param damage the damage per second
     * @return this builder
     */
    public Builder damagePerSecond(double damage) {
      this.damagePerSecond = damage;
      return this;
    }

    /**
     * Builds the traits.
     *
     * @return the traits
     */
    public BlockTraits build() {
      return new BlockTraits(bits, breakTimeSeconds, speedFactor, damagePerSecond);
    }
  }
}
