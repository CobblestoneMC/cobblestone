/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import org.bukkit.World;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.ChunkProvider;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftWorld;

/**
 * A {@link MinecraftWorld} backed by a Bukkit {@link World}. Block access is served by the shared
 * {@link ChunkProvider}; equality is by the world's namespaced key.
 */
final class PaperWorld implements MinecraftWorld {

  private final ChunkProvider provider;
  private final String key;
  private final int minY;
  private final int maxY;
  private final World.Environment environment;

  PaperWorld(World world, ChunkProvider provider) {
    this.provider = provider;
    this.key = world.getKey().asString();
    this.minY = world.getMinHeight();
    this.maxY = world.getMaxHeight() - 1;
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
  public FutureOr<MinecraftBlock> blockAt(Cell cell, Cell destination) {
    return provider.block(cell, this, destination);
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
