/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.ChunkProvider;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.PlatformApi;
import org.spongepowered.api.world.WorldType;
import org.spongepowered.api.world.WorldTypes;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * A {@link MinecraftWorld} backed by a Sponge {@link ServerWorld}. Equality is by the world's
 * namespaced key, so the copies {@link #scopedForSolve()} hands out are interchangeable with this
 * one everywhere a domain is compared or used as a key. Only immutable facts are captured at
 * construction so no live {@link ServerWorld} reference is retained.
 *
 * <p>Block access goes through a {@link ChunkProvider} this world owns. Each solve gets its own, so
 * its cached chunks are sized to its own frontier and released with it rather than competing with
 * every other search on the server for room in one shared cache.
 */
final class SpongeWorld implements MinecraftWorld {

  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final ChunkProvider provider;
  private final String key;
  private final int minY;
  private final int maxY;
  private final Environment environment;

  SpongeWorld(ServerWorld world, PlatformApi<?> platform, ChunkProviderSettings settings) {
    this.platform = platform;
    this.settings = settings;
    this.provider = new ChunkProvider(platform, settings);
    this.key = world.key().asString();
    this.minY = world.min().y();
    this.maxY = world.max().y();
    this.environment = environmentOf(world.worldType());
  }

  private SpongeWorld(SpongeWorld other) {
    this.platform = other.platform;
    this.settings = other.settings;
    this.provider = new ChunkProvider(other.platform, other.settings);
    this.key = other.key;
    this.minY = other.minY;
    this.maxY = other.maxY;
    this.environment = other.environment;
  }

  @Override
  public SpongeWorld scopedForSolve() {
    return new SpongeWorld(this);
  }

  private static Environment environmentOf(WorldType type) {
    if (type.equals(WorldTypes.THE_NETHER.get())) {
      return Environment.NETHER;
    }
    if (type.equals(WorldTypes.THE_END.get())) {
      return Environment.END;
    }
    if (type.equals(WorldTypes.OVERWORLD.get()) || type.equals(WorldTypes.OVERWORLD_CAVES.get())) {
      return Environment.OVERWORLD;
    }
    return Environment.CUSTOM;
  }

  @Override
  public int minY() {
    return minY;
  }

  @Override
  public int maxY() {
    return maxY;
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
  public FutureOr<MinecraftBlock> blockAt(Cell cell, Cell destination) {
    return provider.block(cell, this, destination);
  }

  @Override
  public FutureOr<MinecraftChunk> chunkAt(Cell cell, Cell destination) {
    return provider.chunk(cell, this, destination);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof SpongeWorld other && key.equals(other.key);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }
}
