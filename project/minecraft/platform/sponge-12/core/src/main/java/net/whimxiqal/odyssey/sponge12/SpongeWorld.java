/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12;

import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;
import net.whimxiqal.odyssey.minecraft.ChunkProvider;
import net.whimxiqal.odyssey.minecraft.MinecraftBlock;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import org.spongepowered.api.world.WorldType;
import org.spongepowered.api.world.WorldTypes;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * A {@link MinecraftWorld} backed by a Sponge {@link ServerWorld}. Block access is served by the
 * shared {@link ChunkProvider}; equality is by the world's namespaced key. Only immutable facts are
 * captured at construction so no live {@link ServerWorld} reference is retained.
 */
final class SpongeWorld implements MinecraftWorld {

  private final ChunkProvider provider;
  private final String key;
  private final int minY;
  private final int maxY;
  private final Environment environment;

  SpongeWorld(ServerWorld world, ChunkProvider provider) {
    this.provider = provider;
    this.key = world.key().asString();
    this.minY = world.min().y();
    this.maxY = world.max().y();
    this.environment = environmentOf(world.worldType());
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
  public FutureOr<MinecraftBlock> blockAt(Cell cell) {
    return provider.block(cell, this);
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
