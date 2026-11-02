/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.World;
import org.cobblestonemc.minecraft.MinecraftWorld;

/**
 * A {@link MinecraftWorld} backed by a Bukkit {@link World}. Equality is by the world's namespaced
 * key. Only immutable facts are captured, so no live {@link World} reference is retained.
 */
final class PaperWorld implements MinecraftWorld {

  private final String key;
  private final int minY;
  private final int maxY;
  private final long seed;
  private final World.Environment environment;

  PaperWorld(World world) {
    this.key = world.getKey().asString();
    this.minY = world.getMinHeight();
    this.maxY = world.getMaxHeight() - 1;
    this.seed = world.getSeed();
    this.environment = world.getEnvironment();
  }

  @Override
  public int minY() {
    return this.minY;
  }

  @Override
  public int maxY() {
    return this.maxY;
  }

  @Override
  public long seed() {
    return seed;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public Environment environment() {
    return switch (this.environment) {
      case NORMAL -> Environment.OVERWORLD;
      case NETHER -> Environment.NETHER;
      case THE_END -> Environment.END;
      default -> Environment.CUSTOM;
    };
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof PaperWorld other && key.equals(other.key);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }
}
