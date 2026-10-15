/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import static org.cobblestonemc.minecraft.lod.Capability.BOAT;
import static org.cobblestonemc.minecraft.lod.Capability.FLY;
import static org.cobblestonemc.minecraft.lod.Capability.MINE;
import static org.cobblestonemc.minecraft.lod.Capability.SOUL_SPEED;
import static org.cobblestonemc.minecraft.lod.Capability.SWIM;
import static org.cobblestonemc.minecraft.lod.Capability.WALK;

import java.util.Arrays;

/**
 * A kind of terrain, as the region layer sees it, and which {@link Capability capabilities} cross
 * it at what scale.
 *
 * <p><b>Exactly one class per cell.</b> Every cell belongs to one region of one class. What an
 * agent pays for a region is decided later, by {@link RegionCost}, from the capabilities the class
 * lists here: a flyer and a walker read the same regions, they just price them differently, and the
 * walker cannot enter {@link #AIR} at all.
 *
 * <p><b>A class is terrain that some capability treats differently.</b> Soul sand and soul soil are
 * both ground, but soul sand drags a walker and soul soil does not, and Soul Speed boots work on
 * both: three capabilities, two answers, so two classes. Water and its surface are both swum, but
 * only the surface carries a boat. A new kind of terrain earns a class when an agent would cross it
 * at a different rate than a class that already exists; otherwise it belongs to that class.
 *
 * <p><b>Each capability's factor scales its base rate on this terrain.</b> A factor of 2.5 on soul
 * sand is {@code 1 / 0.4}, the block's speed factor, so it mirrors what the fine search charges. An
 * agent crosses by whichever listed capability is cheapest for it.
 *
 * <p>The order is stored: a region keeps its class as {@link #ordinal()}. Adding or reordering a
 * class needs {@link RegionColumnBuilder#CLASSIFIER_VERSION} bumped.
 */
public enum RegionClass {

  /** A cell a body can stand in: a solid top below, room for a two-block body. */
  STAND(crossing(WALK, 1), crossing(FLY, 1)),

  /** {@link #STAND}, on soul sand: it drags a walker, but not one in Soul Speed boots. */
  SOUL_SAND(crossing(WALK, 1 / 0.4), crossing(SOUL_SPEED, 1), crossing(FLY, 1)),

  /** {@link #STAND}, on soul soil: ordinary ground to a walker, and fast in Soul Speed boots. */
  SOUL_SOIL(crossing(WALK, 1), crossing(SOUL_SPEED, 1), crossing(FLY, 1)),

  /** Water with water above it: only swum. */
  WATER(crossing(SWIM, 1)),

  /** Water open to the air above: swum, or crossed by boat. */
  WATER_SURFACE(crossing(SWIM, 1), crossing(BOAT, 1)),

  /** Ladders, vines and scaffolding. */
  CLIMB(crossing(Capability.CLIMB, 1), crossing(FLY, 1)),

  /** Open space nothing stands in: crossed by flying, or fallen through. */
  AIR(crossing(FLY, 1)),

  /**
   * Breakable material: dug through, or for an agent that cannot dig, a wall at a price.
   *
   * <p>Mining is priced from the region's break time, not from a rate; see {@link RegionCost}.
   */
  SOLID(crossing(MINE, 1));

  /** Every class, cached; {@code values()} allocates. */
  public static final RegionClass[] ALL = values();

  /**
   * Per capability, by ordinal: its factor here, or {@code NaN} if it does not cross this class.
   */
  private final double[] factors;

  RegionClass(Crossing... crossings) {
    this.factors = new double[Capability.ALL.length];
    Arrays.fill(factors, Double.NaN);
    for (Crossing crossing : crossings) {
      factors[crossing.capability().ordinal()] = crossing.factor();
    }
  }

  private static Crossing crossing(Capability capability, double factor) {
    return new Crossing(capability, factor);
  }

  /**
   * Returns how this terrain scales a capability's rate, or {@code NaN} if the capability does not
   * cross it.
   *
   * @param capability the capability
   * @return the factor on its rate per block
   */
  public double factor(Capability capability) {
    return factors[capability.ordinal()];
  }

  /**
   * Returns whether a walking body stands in cells of this class, so moves between them step up and
   * down as well as across.
   *
   * @return {@code true} for the classes of ground
   */
  public boolean standing() {
    return this == STAND || this == SOUL_SAND || this == SOUL_SOIL;
  }

  /**
   * Returns whether this class is water, surface or not.
   *
   * @return {@code true} for {@link #WATER} and {@link #WATER_SURFACE}
   */
  public boolean water() {
    return this == WATER || this == WATER_SURFACE;
  }

  /** A capability that crosses a class, and the factor the class puts on its rate. */
  private record Crossing(Capability capability, double factor) {}
}
