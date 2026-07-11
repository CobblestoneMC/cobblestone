/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.minecraft.api.ChunkLoadPolicy;
import net.whimxiqal.odyssey.minecraft.api.MinecraftChunk;
import net.whimxiqal.odyssey.minecraft.api.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.api.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.PlatformApi;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * The Paper {@link PlatformApi}: resolves worlds by their namespaced key and snapshots chunks
 * according to the load policy. Snapshots are taken on the chunk's owning thread (via the async
 * chunk load or the region scheduler), then read freely from search worker threads.
 */
final class PaperPlatformApi implements PlatformApi {

  private final Plugin plugin;
  private final PaperScheduler scheduler;

  PaperPlatformApi(Plugin plugin, PaperScheduler scheduler) {
    this.plugin = plugin;
    this.scheduler = scheduler;
  }

  @Override
  public MinecraftScheduler scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<Optional<MinecraftChunk>> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy) {
    NamespacedKey key = NamespacedKey.fromString(world.key());
    World bukkit = key == null ? null : Bukkit.getWorld(key);
    if (bukkit == null) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    int minY = bukkit.getMinHeight();
    int maxY = bukkit.getMaxHeight() - 1;

    if (policy == ChunkLoadPolicy.LOADED_ONLY) {
      if (!bukkit.isChunkLoaded(chunkX, chunkZ)) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      CompletableFuture<Optional<MinecraftChunk>> future = new CompletableFuture<>();
      Bukkit.getRegionScheduler().execute(plugin, bukkit, chunkX, chunkZ, () -> {
        ChunkSnapshot snapshot = bukkit.getChunkAt(chunkX, chunkZ).getChunkSnapshot();
        future.complete(Optional.of(new PaperChunk(snapshot, chunkX, chunkZ, minY, maxY)));
      });
      return future;
    }

    boolean generate = policy == ChunkLoadPolicy.GENERATE;
    return bukkit.getChunkAtAsync(chunkX, chunkZ, generate).thenApply(chunk ->
        Optional.<MinecraftChunk>of(new PaperChunk(chunk.getChunkSnapshot(), chunkX, chunkZ, minY, maxY)));
  }
}
