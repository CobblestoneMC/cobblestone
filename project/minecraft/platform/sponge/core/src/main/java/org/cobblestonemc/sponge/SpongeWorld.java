/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import org.cobblestonemc.minecraft.MinecraftWorld;
import org.spongepowered.api.world.WorldType;
import org.spongepowered.api.world.WorldTypes;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * A {@link MinecraftWorld} backed by a Sponge {@link ServerWorld}. Equality is by the world's
 * namespaced key. Only immutable facts are captured at construction so no live {@link ServerWorld}
 * reference is retained.
 */
final class SpongeWorld implements MinecraftWorld {

  private final String key;
  private final int minY;
  private final int maxY;
  private final long seed;
  private final Environment environment;

  SpongeWorld(ServerWorld world) {
    this.key = world.key().asString();
    this.minY = world.min().y();
    this.maxY = world.max().y();
    this.seed = world.properties().worldGenerationConfig().seed();
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
  public long seed() {
    return seed;
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
    return o instanceof SpongeWorld other && key.equals(other.key);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }
}
