/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.ChunkProvider;
import org.cobblestonemc.minecraft.ChunkProviderSettings;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.PlatformApi;

/**
 * A {@link MinecraftWorld} over one world of a capture.
 *
 * <p><b>Mirrors {@code PaperWorld} deliberately, down to owning a {@link ChunkProvider} and handing
 * each solve its own through {@link #scopedForSolve()}.</b> The per-solve chunk cache — its size,
 * its eviction, the way a frontier crossing a chunk border hits or misses it — is part of what the
 * benchmark exists to measure. A platform that cached differently from production would produce
 * numbers about itself.
 *
 * <p>Equality is by world key, as {@link org.cobblestonemc.Domain} requires of a scoped view.
 */
public final class StonebrickWorld implements MinecraftWorld {

  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final ChunkProvider provider;
  private final String key;
  private final int minY;
  private final int maxY;
  private final Environment environment;

  StonebrickWorld(
      PlatformApi<?> platform,
      ChunkProviderSettings settings,
      String key,
      int minY,
      int maxY,
      Environment environment) {
    this.platform = platform;
    this.settings = settings;
    this.provider = new ChunkProvider(platform, settings);
    this.key = key;
    this.minY = minY;
    this.maxY = maxY;
    this.environment = environment;
  }

  private StonebrickWorld(StonebrickWorld other) {
    this.platform = other.platform;
    this.settings = other.settings;
    this.provider = new ChunkProvider(other.platform, other.settings);
    this.key = other.key;
    this.minY = other.minY;
    this.maxY = other.maxY;
    this.environment = other.environment;
  }

  @Override
  public StonebrickWorld scopedForSolve() {
    return new StonebrickWorld(this);
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
