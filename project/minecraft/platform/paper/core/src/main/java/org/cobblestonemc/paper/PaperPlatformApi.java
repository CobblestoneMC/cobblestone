/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.PlatformApi;

/**
 * The Paper {@link PlatformApi}: resolves worlds by their namespaced key and snapshots chunks
 * according to the load policy. Snapshots are taken on the chunk's owning thread (via the async
 * chunk load or the region scheduler), then read freely from search worker threads.
 *
 * <p>A chunk that is generated but not loaded is the exception: rather than loading it, it is read
 * off disk by {@link NMSChunkReader}, which never puts it in the chunk system at all. That is an
 * optimization, not a contract — if the read fails, or this server's internals are not the ones
 * Cobblestone was built against, the chunk is loaded through the server instead. Callers see one
 * answer either way: a chunk, or {@link MinecraftChunk.Unknown}, or a failed future when neither
 * route could produce it.
 */
final class PaperPlatformApi implements PlatformApi<Entity> {

  private final Plugin plugin;
  private final PaperScheduler scheduler;
  private final NMSChunkReader reader;

  PaperPlatformApi(Plugin plugin, PaperScheduler scheduler, CobblestoneLogger logger) {
    this.plugin = plugin;
    this.scheduler = scheduler;
    this.reader = new NMSChunkReader(logger);
  }

  @Override
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<MinecraftChunk> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent) {
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

    // Already in memory, or terrain we are allowed to generate: go through the chunk system.
    boolean generate = policy == ChunkLoadPolicy.ALLOW_LOAD_AND_GENERATE;
    if (bukkit.isChunkLoaded(chunkX, chunkZ)
        || (generate && !bukkit.isChunkGenerated(chunkX, chunkZ))) {
      return loadThroughServer(bukkit, chunkX, chunkZ, generate, urgent);
    }
    // Generated but not loaded: read the saved blocks without loading the chunk.
    if (!reader.available()) {
      // This server's internals aren't the ones we were built against (NMSSupport has said so,
      // once). Load the chunk through the server instead — slower and heavier, but correct.
      return loadThroughServer(bukkit, chunkX, chunkZ, false, urgent);
    }
    return reader
        .read(chunkX, chunkZ, bukkit, urgent)
        .<MinecraftChunk>thenApply(chunk -> chunk == null ? MinecraftChunk.Unknown.INSTANCE : chunk)
        // Failing to read the chunk off disk is this class's problem, not the caller's. The chunk
        // provider's vocabulary is "could or couldn't source this chunk", not "couldn't source it
        // the fast way", so a failed fast path falls back to the slow one and the caller never
        // learns there were two. Only if the server cannot load it either does the future fail.
        .exceptionallyCompose(
            error -> {
              reader.reportFailure(error, chunkX, chunkZ, bukkit);
              return loadThroughServer(bukkit, chunkX, chunkZ, false, urgent);
            });
  }

  /**
   * Stops issuing offline reads and calls off the ones the server has not started.
   *
   * <p>Reads already underway are left to finish; {@code ChunkProvider}'s drain is what waits for
   * those, and it covers every platform fetch rather than only these.
   */
  @Override
  public void shutdown() {
    reader.shutdown();
  }

  private CompletableFuture<MinecraftChunk> loadThroughServer(
      World bukkit, int chunkX, int chunkZ, boolean generate, boolean urgent) {
    return bukkit
        .getChunkAtAsync(chunkX, chunkZ, generate, urgent)
        .thenApply(
            chunk ->
                chunk == null
                    ? MinecraftChunk.Unknown.INSTANCE
                    : new PaperChunk(chunk.getChunkSnapshot()));
  }
}
