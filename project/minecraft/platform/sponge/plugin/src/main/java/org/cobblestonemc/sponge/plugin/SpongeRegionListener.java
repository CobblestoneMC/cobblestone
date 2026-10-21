/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.lod.RegionStores;
import org.cobblestonemc.sponge.WorldWrapper;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.transaction.BlockTransactionReceipt;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.block.ChangeBlockEvent;
import org.spongepowered.api.event.world.SaveWorldEvent;
import org.spongepowered.api.event.world.chunk.ChunkEvent;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Keeps the region summaries honest, and gets them made early: the Sponge counterpart of the Paper
 * plugin's {@code PaperRegionListener}.
 *
 * <p>Sponge reports every block change through one event, after it happened, so a single listener
 * covers what Paper needs a dozen for: each changed block forgets its chunk's summary, to be made
 * again when a search next needs it. A loaded chunk's summary stays in memory while the chunk does:
 * a chunk that loads is summarized in the background if it is not already known, while the
 * background queue has room, and when it unloads its summary is kept only as the memory budget
 * allows.
 */
final class SpongeRegionListener {

  private final RegionStores stores;
  private final WorldWrapper worlds;
  private final LoadedChunks chunks;

  SpongeRegionListener(RegionStores stores, WorldWrapper worlds, LoadedChunks chunks) {
    this.stores = stores;
    this.worlds = worlds;
    this.chunks = chunks;
  }

  /** Every block that changed forgets its chunk's summary. */
  @Listener(order = Order.POST)
  public void onChange(ChangeBlockEvent.Post event) {
    MinecraftWorld world = worlds.wrap(event.world());
    for (BlockTransactionReceipt receipt : event.receipts()) {
      Vector3i position = receipt.originalBlock().position();
      stores.invalidate(world, position.x() >> 4, position.z() >> 4);
    }
  }

  /** The world has saved: write the summaries too, so a crash after it loses nothing. */
  @Listener(order = Order.POST)
  public void onSave(SaveWorldEvent.Post event) {
    stores.flush();
  }

  @Listener(order = Order.POST)
  public void onChunkLoad(ChunkEvent.Load event) {
    Optional<ServerWorld> loaded = Sponge.server().worldManager().world(event.worldKey());
    if (loaded.isEmpty()) {
      return;
    }
    ServerWorld world = loaded.get();
    MinecraftWorld wrapped = worlds.wrap(world);
    int chunkX = event.chunkPosition().x();
    int chunkZ = event.chunkPosition().z();
    stores.chunkLoaded(wrapped, chunkX, chunkZ);
    if (!stores.prebuildRoom() || stores.known(wrapped, chunkX, chunkZ)) {
      return;
    }
    chunks
        .readLoaded(world, chunkX, chunkZ)
        .thenAccept(
            read -> {
              if (read != null) {
                stores.prebuild(wrapped, chunkX, chunkZ, read);
              }
            });
  }

  @Listener(order = Order.POST)
  public void onChunkUnload(ChunkEvent.Unload.Post event) {
    Sponge.server()
        .worldManager()
        .world(event.worldKey())
        .ifPresent(
            world ->
                stores.chunkUnloaded(
                    worlds.wrap(world), event.chunkPosition().x(), event.chunkPosition().z()));
  }

  /** Reads a chunk the server has loaded, without loading anything. */
  interface LoadedChunks {
    CompletableFuture<MinecraftChunk> readLoaded(ServerWorld world, int chunkX, int chunkZ);
  }
}
