/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.ChunkFetch;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.PlatformApi;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.plugin.PluginContainer;

/**
 * The Sponge {@link PlatformApi}: resolves worlds by key and hands chunk fetches to the {@link
 * SpongeChunkLoader}, which owns the ticket machinery Sponge requires to read a chunk that is not
 * already loaded.
 *
 * <p>Reading a chunk that is not loaded is the one thing that varies by Minecraft version, so it
 * arrives as an {@link OfflineChunkSource} from the module built for this server's version — or as
 * {@link OfflineChunkSource#UNAVAILABLE}, in which case the loader tickets every chunk exactly as
 * it always has.
 */
final class SpongePlatformApi implements PlatformApi<Entity> {

  private final SpongeScheduler scheduler;
  private final SpongeChunkLoader chunks;

  private final OfflineChunkSource offline;

  private final LoadedChunkIndex loaded;

  SpongePlatformApi(
      SpongeScheduler scheduler,
      CobblestoneLogger logger,
      IntSupplier maxLoadRequests,
      OfflineChunkSource offline) {
    this.scheduler = scheduler;
    this.offline = offline;
    this.loaded = new LoadedChunkIndex(logger);
    this.chunks = new SpongeChunkLoader(scheduler, logger, maxLoadRequests, offline, loaded);
  }

  @Override
  public void shutdown() {
    chunks.shutdown(); // first, so reads the source calls off are not handed to the tickets
    offline.shutdown();
  }

  public void registerListeners(PluginContainer plugin) {
    Sponge.eventManager().registerListeners(plugin, chunks);
    Sponge.eventManager().registerListeners(plugin, loaded);
  }

  /**
   * Takes stock of the worlds already running. Server thread only; call once the engine has
   * started.
   *
   * <p>Events alone only describe what happens from now on, and what is already loaded is exactly
   * what a search is most likely to reach for first.
   */
  public void surveyWorlds() {
    loaded.seedAll();
    for (ServerWorld world : Sponge.server().worldManager().worlds()) {
      offline.prepare(world);
    }
  }

  @Override
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<ChunkFetch> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent) {
    Optional<ServerWorld> resolved =
        Sponge.server().worldManager().world(ResourceKey.resolve(world.key()));
    if (resolved.isEmpty()) {
      return CompletableFuture.completedFuture(ChunkFetch.Failed.permanent());
    }
    return chunks.fetch(resolved.get(), chunkX, chunkZ, policy, urgent);
  }
}
