/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import org.cobblestonemc.minecraft.MinecraftWorld;

/**
 * A {@link MinecraftWorld} over one world of a capture. Equality is by world key, as {@link
 * org.cobblestonemc.Domain} requires.
 */
public final class StonebrickWorld implements MinecraftWorld {

  private final String key;
  private final int minY;
  private final int maxY;
  private final Environment environment;

  StonebrickWorld(String key, int minY, int maxY, Environment environment) {
    this.key = key;
    this.minY = minY;
    this.maxY = maxY;
    this.environment = environment;
  }

  @Override
  public int minY() {
    return minY;
  }

  @Override
  public int maxY() {
    return maxY;
  }

  /** None recorded: a capture's terrain never changes under its key. */
  @Override
  public long seed() {
    return 0;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public Environment environment() {
    return environment;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof StonebrickWorld other && key.equals(other.key);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }

  @Override
  public String toString() {
    return "StonebrickWorld[" + key + "]";
  }
}
