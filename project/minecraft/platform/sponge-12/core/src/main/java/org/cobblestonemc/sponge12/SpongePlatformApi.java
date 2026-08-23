/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.ChunkLoadPolicy;
import org.cobblestonemc.minecraft.MinecraftChunk;
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
 */
final class SpongePlatformApi implements PlatformApi<Entity> {

  private final SpongeScheduler scheduler;
  private final SpongeChunkLoader chunks;

  SpongePlatformApi(
      SpongeScheduler scheduler, CobblestoneLogger logger, IntSupplier maxLoadRequests) {
    this.scheduler = scheduler;
    this.chunks = new SpongeChunkLoader(scheduler, logger, maxLoadRequests);
  }

  public void registerListeners(PluginContainer plugin) {
    Sponge.eventManager().registerListeners(plugin, chunks);
  }

  @Override
  public MinecraftScheduler<Entity> scheduler() {
    return scheduler;
  }

  @Override
  public CompletableFuture<MinecraftChunk> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent) {
    Optional<ServerWorld> resolved =
        Sponge.server().worldManager().world(ResourceKey.resolve(world.key()));
    if (resolved.isEmpty()) {
      return CompletableFuture.completedFuture(MinecraftChunk.Unknown.INSTANCE);
    }
    return chunks.fetch(resolved.get(), chunkX, chunkZ, policy, urgent);
  }
}
