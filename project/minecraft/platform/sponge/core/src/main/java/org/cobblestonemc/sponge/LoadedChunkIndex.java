/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.ScopedCobblestoneLogger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.world.LoadWorldEvent;
import org.spongepowered.api.event.world.UnloadWorldEvent;
import org.spongepowered.api.event.world.chunk.ChunkEvent;
import org.spongepowered.api.world.chunk.WorldChunk;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Remembers which chunks are loaded, so that question can be asked from any thread.
 *
 * <p><b>Why this exists.</b> Deciding how to obtain a chunk starts with "is it already in memory",
 * and the only way to ask Sponge is {@code ServerWorld#isChunkLoaded}, which needs the server
 * thread — SpongeAPI documents world access off the main thread as unsafe, and vanilla's own chunk
 * source branches on the calling thread. So every fetch used to hop to the main thread before it
 * could do anything, and a hop costs a whole tick: a search measured 59.5 seconds parked across
 * 1342 fetches, a mean of 44ms each, which is one tick apiece. Almost all of those fetches then
 * went on to read a file, which needs no server thread at all.
 *
 * <p>Keeping the answer in a concurrent set turns that question into a hash lookup. A chunk that is
 * not loaded — the overwhelming majority of what a search reaches for — goes straight to the disk
 * read on an IO thread, and only a chunk that really is loaded pays for the hop.
 *
 * <p><b>Two races, both benign.</b> The index can say loaded for a chunk that has since unloaded:
 * the hop happens, finds it gone, and falls back to reading it off disk, which is where it would
 * have gone anyway. Or it can say not loaded for a chunk that has since loaded and been edited, and
 * the search reads the saved version — stale by however long the chunk has been loaded. Cobblestone
 * accepts that: searches run in seconds, and a snapshot was never going to be perfectly current.
 *
 * <p><b>Seeded, not just observed.</b> Events alone would miss everything loaded before Cobblestone
 * started — spawn chunks, anything a player was standing in — and those would read stale from disk
 * for the rest of the session rather than for a moment. Each world is therefore enumerated once on
 * the server thread when it is registered.
 */
final class LoadedChunkIndex {

  private final CobblestoneLogger logger;

  /**
   * Loaded chunk positions by world, packed as {@code x} in the high word and {@code z} the low.
   */
  private final Map<String, Set<Long>> byWorld = new ConcurrentHashMap<>();

  LoadedChunkIndex(CobblestoneLogger logger) {
    this.logger = new ScopedCobblestoneLogger(logger, "LoadedChunkIndex");
  }

  /**
   * Returns whether a chunk is believed to be loaded. Safe from any thread.
   *
   * @param world the world's key
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return {@code true} if the chunk is thought to be in memory
   */
  boolean isLoaded(String worldKey, int chunkX, int chunkZ) {
    Set<Long> loaded = byWorld.get(worldKey);
    return loaded != null && loaded.contains(pack(chunkX, chunkZ));
  }

  /** Records a chunk as loaded. */
  void markLoaded(String worldKey, int chunkX, int chunkZ) {
    byWorld
        .computeIfAbsent(worldKey, key -> ConcurrentHashMap.newKeySet())
        .add(pack(chunkX, chunkZ));
  }

  /** Records a chunk as no longer loaded. */
  void markUnloaded(String worldKey, int chunkX, int chunkZ) {
    Set<Long> loaded = byWorld.get(worldKey);
    if (loaded != null) {
      loaded.remove(pack(chunkX, chunkZ));
    }
  }

  /**
   * Records every chunk a world already has in memory. Server thread only.
   *
   * @param world the world to enumerate
   */
  void seed(ServerWorld world) {
    Set<Long> loaded = ConcurrentHashMap.newKeySet();
    int count = 0;
    for (WorldChunk chunk : world.loadedChunks()) {
      Vector3i position = chunk.chunkPosition();
      loaded.add(pack(position.x(), position.z()));
      count++;
    }
    byWorld.put(world.key().asString(), loaded);
    logger.debug("Indexed {} chunk(s) already loaded in {}", count, world.key());
  }

  /** Seeds every world already running. Server thread only; call once Cobblestone is up. */
  void seedAll() {
    for (ServerWorld world : Sponge.server().worldManager().worlds()) {
      seed(world);
    }
  }

  @Listener
  public void onWorldLoad(LoadWorldEvent event) {
    seed(event.world());
  }

  @Listener
  public void onWorldUnload(UnloadWorldEvent event) {
    byWorld.remove(event.world().key().asString());
  }

  @Listener
  public void onChunkLoad(ChunkEvent.Load event) {
    Vector3i position = event.chunkPosition();
    markLoaded(event.worldKey().asString(), position.x(), position.z());
  }

  @Listener
  public void onChunkUnload(ChunkEvent.Unload.Post event) {
    Vector3i position = event.chunkPosition();
    markUnloaded(event.worldKey().asString(), position.x(), position.z());
  }

  private static long pack(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
  }
}
