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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
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
 *
 * <p><b>And the recently departed.</b> A chunk that has just unloaded is not quite on disk yet: its
 * save is written a moment after it leaves memory. So the index also remembers when each chunk
 * unloaded, for {@link #SAVE_GRACE_MILLIS}, which lets a disk read that comes back empty for such a
 * chunk be told apart from one for a chunk that was never generated. See {@link #recentlyUnloaded}.
 */
final class LoadedChunkIndex {

  /**
   * How long after a chunk unloads its save may still be on its way to disk.
   *
   * <p>Vanilla serializes a chunk as it unloads and hands the bytes to a background writer, so the
   * region file catches up a moment later — normally well under a second, longer on a busy disk.
   * Generous on purpose: the only cost of overestimating is that a chunk which really was never
   * saved gets retried a few times before a search gives up on it.
   */
  static final long SAVE_GRACE_MILLIS = 30_000L;

  private final CobblestoneLogger logger;
  private final LongSupplier clock;

  /**
   * Loaded chunk positions by world, packed as {@code x} in the high word and {@code z} the low.
   */
  private final Map<String, Set<Long>> byWorld = new ConcurrentHashMap<>();

  /**
   * When each recently unloaded chunk unloaded, by world, packed as in {@link #byWorld}. Entries
   * older than {@link #SAVE_GRACE_MILLIS} are dead weight and are swept out now and then.
   */
  private final Map<String, Map<Long, Long>> unloadedAt = new ConcurrentHashMap<>();

  private final AtomicLong lastSweptAt = new AtomicLong();

  LoadedChunkIndex(CobblestoneLogger logger) {
    this(logger, System::currentTimeMillis);
  }

  LoadedChunkIndex(CobblestoneLogger logger, LongSupplier clock) {
    this.logger = new ScopedCobblestoneLogger(logger, "LoadedChunkIndex");
    this.clock = clock;
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

  /**
   * Returns whether a chunk unloaded so recently that its save may not have reached disk yet. Safe
   * from any thread.
   *
   * <p>A disk read that finds nothing for such a chunk is not evidence the chunk does not exist,
   * only that the write is still in flight.
   *
   * @param worldKey the world's key
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return {@code true} if the chunk unloaded within the last {@link #SAVE_GRACE_MILLIS}
   */
  boolean recentlyUnloaded(String worldKey, int chunkX, int chunkZ) {
    Map<Long, Long> unloaded = unloadedAt.get(worldKey);
    if (unloaded == null) {
      return false;
    }
    Long at = unloaded.get(pack(chunkX, chunkZ));
    return at != null && clock.getAsLong() - at < SAVE_GRACE_MILLIS;
  }

  /** Records a chunk as loaded. */
  void markLoaded(String worldKey, int chunkX, int chunkZ) {
    long packed = pack(chunkX, chunkZ);
    byWorld.computeIfAbsent(worldKey, key -> ConcurrentHashMap.newKeySet()).add(packed);
    Map<Long, Long> unloaded = unloadedAt.get(worldKey);
    if (unloaded != null) {
      unloaded.remove(packed);
    }
  }

  /** Records a chunk as no longer loaded, and when. */
  void markUnloaded(String worldKey, int chunkX, int chunkZ) {
    long packed = pack(chunkX, chunkZ);
    Set<Long> loaded = byWorld.get(worldKey);
    if (loaded != null) {
      loaded.remove(packed);
    }
    long now = clock.getAsLong();
    unloadedAt.computeIfAbsent(worldKey, key -> new ConcurrentHashMap<>()).put(packed, now);
    sweep(now);
  }

  /**
   * Drops unload times too old to matter, at most once per {@link #SAVE_GRACE_MILLIS}, so the map
   * holds no more than the chunks unloaded across about two grace periods.
   */
  private void sweep(long now) {
    long last = lastSweptAt.get();
    if (now - last < SAVE_GRACE_MILLIS || !lastSweptAt.compareAndSet(last, now)) {
      return;
    }
    for (Map<Long, Long> unloaded : unloadedAt.values()) {
      unloaded.values().removeIf(at -> now - at >= SAVE_GRACE_MILLIS);
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
    unloadedAt.remove(event.world().key().asString());
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
