/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper;

import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.minecraft.ChunkLoadPolicy;
import net.whimxiqal.odyssey.minecraft.MinecraftChunk;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.PlatformApi;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * The Paper {@link PlatformApi}: resolves worlds by their namespaced key and snapshots chunks
 * according to the load policy. Snapshots are taken on the chunk's owning thread (via the async
 * chunk load or the region scheduler), then read freely from search worker threads.
 */
final class PaperPlatformApi implements PlatformApi<Entity> {

  private final Plugin plugin;
  private final PaperScheduler scheduler;

  PaperPlatformApi(Plugin plugin, PaperScheduler scheduler) {
    this.plugin = plugin;
    this.scheduler = scheduler;
  }

  @Override
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<MinecraftChunk> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy) {
    NamespacedKey key = NamespacedKey.fromString(world.key());
    World bukkit = key == null ? null : Bukkit.getWorld(key);
    if (bukkit == null) {
      return CompletableFuture.completedFuture(MinecraftChunk.Unknown.INSTANCE);
    }

    if (policy == ChunkLoadPolicy.LOADED_ONLY) {
      if (!bukkit.isChunkLoaded(chunkX, chunkZ)) {
        return CompletableFuture.completedFuture(MinecraftChunk.Unknown.INSTANCE);
      }
      CompletableFuture<MinecraftChunk> future = new CompletableFuture<>();
      Bukkit.getRegionScheduler()
          .execute(
              plugin,
              bukkit,
              chunkX,
              chunkZ,
              () -> {
                ChunkSnapshot snapshot = bukkit.getChunkAt(chunkX, chunkZ).getChunkSnapshot();
                future.complete(new PaperChunk(snapshot));
              });
      return future;
    }

    boolean generate = policy == ChunkLoadPolicy.GENERATE;
    return bukkit
        .getChunkAtAsync(chunkX, chunkZ, generate)
        .thenApply(
            chunk ->
                chunk == null
                    ? MinecraftChunk.Unknown.INSTANCE
                    : new PaperChunk(chunk.getChunkSnapshot()));
  }
}
