/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.ChunkFetch;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.PlatformApi;

/**
 * The Paper {@link PlatformApi}: resolves worlds by their namespaced key and hands chunk fetches to
 * the {@link PaperChunkFetcher}, which decides how each one is obtained.
 */
final class PaperPlatformApi implements PlatformApi<Entity> {

  /**
   * How long shutdown waits for chunk reads already underway. Long enough for a queue of
   * region-file reads to drain on a busy disk, short enough that one that will never complete
   * cannot hold the server open.
   */
  private static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  private final PaperScheduler scheduler;
  private final PaperChunkFetcher chunks;

  PaperPlatformApi(Plugin plugin, PaperScheduler scheduler, CobblestoneLogger logger) {
    this.scheduler = scheduler;
    this.chunks = new PaperChunkFetcher(plugin, logger);
  }

  @Override
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<ChunkFetch> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent) {
    NamespacedKey key = NamespacedKey.fromString(world.key());
    World bukkit = key == null ? null : Bukkit.getWorld(key);
    if (bukkit == null) {
      return CompletableFuture.completedFuture(ChunkFetch.Failed.permanent());
    }
    return chunks.fetch(bukkit, chunkX, chunkZ, policy, urgent);
  }

  @Override
  public void shutdown() {
    chunks.shutdown(SHUTDOWN_DRAIN_MILLIS);
  }
}
