/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import org.cobblestonemc.minecraft.Direction;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.stonebrick.format.BlockTraits;

/**
 * A {@link MinecraftBlock} backed by traits a capture recorded from the real server.
 *
 * <p>Every answer here was computed on a live server by the same code production uses, then frozen.
 * There is no trait logic in this class and there must never be any: the moment this file starts
 * deciding that some material is climbable, a benchmark stops predicting server behavior and starts
 * predicting its own.
 *
 * <p>One instance per palette entry per column, so a lookup is an array index and the hot path
 * allocates nothing.
 */
final class StonebrickBlock implements MinecraftBlock {

  /**
   * {@link BlockTraits#DIRECTIONS} resolved to this build's {@link Direction} constants, by name.
   *
   * <p>By name rather than by ordinal for the same reason the copier resolves it that way: the
   * capture is a wire format written months earlier, and binding its face order to an enum's
   * current ordering would silently reinterpret every partial block the day a constant moved.
   */
  private static final int[] FACE_OF_DIRECTION = resolveFaces();

  private final BlockTraits traits;

  StonebrickBlock(BlockTraits traits) {
    this.traits = traits;
  }

  private static int[] resolveFaces() {
    int[] faces = new int[Direction.values().length];
    java.util.Arrays.fill(faces, -1);
    for (int i = 0; i < BlockTraits.DIRECTIONS.length; i++) {
      faces[Direction.valueOf(BlockTraits.DIRECTIONS[i]).ordinal()] = i;
    }
    for (int i = 0; i < faces.length; i++) {
      if (faces[i] < 0) {
        throw new IllegalStateException(
            "Direction."
                + Direction.values()[i]
                + " has no slot in the capture format's"
                + " direction order; BlockTraits.DIRECTIONS needs updating and every capture's"
                + " trait table needs regenerating");
      }
    }
    return faces;
  }

  @Override
  public boolean isPassable() {
    return traits.has(BlockTraits.PASSABLE);
  }

  @Override
  public boolean isSolidTop() {
    return traits.has(BlockTraits.SOLID_TOP);
  }

  @Override
  public boolean isHalfHeight() {
    return traits.has(BlockTraits.HALF_HEIGHT);
  }

  @Override
  public boolean isEnterable(Direction from) {
    return traits.enterableFrom(FACE_OF_DIRECTION[from.ordinal()]);
  }

  @Override
  public boolean isExitable(Direction to) {
    return traits.exitableToward(FACE_OF_DIRECTION[to.ordinal()]);
  }

  @Override
  public boolean isWater() {
    return traits.has(BlockTraits.WATER);
  }

  @Override
  public boolean isLava() {
    return traits.has(BlockTraits.LAVA);
  }

  @Override
  public boolean isClimbable() {
    return traits.has(BlockTraits.CLIMBABLE);
  }

  @Override
  public boolean isScaffolding() {
    return traits.has(BlockTraits.SCAFFOLDING);
  }

  @Override
  public boolean isDangerous() {
    return traits.has(BlockTraits.DANGEROUS);
  }

  @Override
  public double damagePerSecond() {
    return traits.damagePerSecond();
  }

  @Override
  public double breakTimeSeconds() {
    return traits.breakTimeSeconds();
  }

  @Override
  public boolean supportsBoat() {
    return traits.has(BlockTraits.SUPPORTS_BOAT);
  }

  @Override
  public boolean isSoulSand() {
    return traits.has(BlockTraits.SOUL_SAND);
  }

  @Override
  public double speedFactor() {
    return traits.speedFactor();
  }

  @Override
  public boolean isDoor() {
    return traits.has(BlockTraits.DOOR);
  }

  @Override
  public boolean isTrapdoor() {
    return traits.has(BlockTraits.TRAPDOOR);
  }

  @Override
  public boolean isOpen() {
    return traits.has(BlockTraits.OPEN);
  }

  @Override
  public boolean opensByHand() {
    return traits.has(BlockTraits.OPENS_BY_HAND);
  }

  @Override
  public boolean isPressurePlate() {
    return traits.has(BlockTraits.PRESSURE_PLATE);
  }
}
