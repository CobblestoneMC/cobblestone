/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.FutureOr;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import net.whimxiqal.odyssey.minecraft.api.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import org.bukkit.World;

/**
 * A {@link MinecraftWorld} backed by a Bukkit {@link World}. Block access is served by the shared
 * {@link ChunkProvider}; equality is by the world's namespaced key.
 */
final class PaperWorld implements MinecraftWorld {

  private final World world;
  private final ChunkProvider provider;
  private final String key;

  PaperWorld(World world, ChunkProvider provider) {
    this.world = world;
    this.provider = provider;
    this.key = world.getKey().asString();
  }

  @Override
  public int minY() {
    return world.getMinHeight();
  }

  @Override
  public int maxY() {
    return world.getMaxHeight() - 1;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public Environment environment() {
    return switch (world.getEnvironment()) {
      case NORMAL -> Environment.OVERWORLD;
      case NETHER -> Environment.NETHER;
      case THE_END -> Environment.END;
      default -> Environment.CUSTOM;
    };
  }

  @Override
  public FutureOr<MinecraftBlock> blockAt(Cell cell) {
    return provider.block(cell, this);
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
