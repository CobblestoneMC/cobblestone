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
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.PlatformApi;

/**
 * A {@link MinecraftWorld} backed by a Bukkit {@link World}. Equality is by the world's namespaced
 * key, so the copies {@link #scopedForSolve()} hands out are interchangeable with this one
 * everywhere a domain is compared or used as a key.
 *
 * <p>Block access goes through a {@link ChunkProvider} this world owns. Each solve gets its own
 * through {@link #scopedForSolve()}, so its cached chunks are sized to its own frontier and are
 * released with it rather than competing with every other search on the server for room in one
 * shared cache.
 */
final class PaperWorld implements MinecraftWorld {

  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final ChunkProvider provider;
  private final String key;
  private final int minY;
  private final int maxY;
  private final World.Environment environment;

  PaperWorld(World world, PlatformApi<?> platform, ChunkProviderSettings settings) {
    this.platform = platform;
    this.settings = settings;
    this.provider = new ChunkProvider(platform, settings);
    this.key = world.getKey().asString();
    this.minY = world.getMinHeight();
    this.maxY = world.getMaxHeight() - 1;
    this.environment = world.getEnvironment();
  }

  private PaperWorld(PaperWorld other) {
    this.platform = other.platform;
    this.settings = other.settings;
    this.provider = new ChunkProvider(other.platform, other.settings);
    this.key = other.key;
    this.minY = other.minY;
    this.maxY = other.maxY;
    this.environment = other.environment;
  }

  @Override
  public PaperWorld scopedForSolve() {
    return new PaperWorld(this);
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
  public FutureOr<MinecraftChunk> chunkAt(Cell cell, Cell destination) {
    return provider.chunk(cell, this, destination);
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
