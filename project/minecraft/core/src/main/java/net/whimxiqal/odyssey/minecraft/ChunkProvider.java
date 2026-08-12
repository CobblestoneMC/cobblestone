/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;

/**
 * A thread-safe, size-bounded (LRU) cache of chunk snapshots, sitting between modes and the
 * platform. A block from a fresh cached chunk is served immediately (a cache hit); a miss triggers
 * a single de-duplicated fetch and is served as a pending {@link FutureOr}. Snapshots older than
 * the staleness window (measured from when they were cached) are discarded on access, and blocks
 * near a chunk border prefetch the adjacent chunk to keep subsequent linear expansions on the fast
 * path.
 *
 * <p>A world implementation delegates {@link MinecraftWorld#blockAt(Cell)} to {@link #block(Cell,
 * MinecraftWorld)}.
 */
public final class ChunkProvider {

  public static final int CHUNK_PREFETCH_MARGIN = 32;
  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final LongSupplier clock;

  private final Object lock = new Object();
  private final Map<ChunkKey, Cached> cache;
  private final Map<ChunkKey, CompletableFuture<MinecraftChunk>> inFlight = new HashMap<>();

  /**
   * Creates a chunk provider.
   *
   * @param platform the platform to fetch snapshots from
   * @param settings the cache tunables
   */
  public ChunkProvider(PlatformApi<?> platform, ChunkProviderSettings settings) {
    this(platform, settings, System::currentTimeMillis);
  }

  ChunkProvider(PlatformApi<?> platform, ChunkProviderSettings settings, LongSupplier clock) {
    this.platform = platform;
    this.settings = settings;
    this.clock = clock;
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<ChunkKey, Cached> eldest) {
            return size() > settings.maxCachedChunks();
          }
        };
  }

  /**
   * Returns the block at {@code cell} in {@code world}, immediate on a cache hit or pending on a
   * miss. Cells outside the world's vertical bounds resolve to an impassable block.
   *
   * @param cell the cell
   * @param world the world
   * @return the block, immediate or pending
   */
  public FutureOr<MinecraftBlock> block(Cell cell, MinecraftWorld world) {
    if (cell.y() < world.minY() || cell.y() > world.maxY()) {
      return FutureOr.of(UnknownBlock.INSTANCE);
    }
    int chunkX = cell.x() >> 4;
    int chunkZ = cell.z() >> 4;
    ChunkKey key = new ChunkKey(world.key(), chunkX, chunkZ);
    synchronized (lock) {
      Cached cached = cache.get(key);

      // trigger read-ahead around this cell if we have not seen this block requested
      // or if we have only requested it because it was part of another prefetch
      if (cached == null) {
        triggerReadAhead(cell, world);
      } else {
        if (cached.prefetched) {
          triggerReadAhead(cell, world);
        }
        if (isStale(cached)) {
          cache.remove(key);
        } else {
          cached.directlyAccessed();
          return FutureOr.of(cached.chunk.block(cell.x() & 15, cell.y(), cell.z() & 15));
        }
      }
      CompletableFuture<MinecraftChunk> fetch = fetchLocked(key, world, false);
      return FutureOr.ofFuture(
          fetch.thenApply(snapshot -> snapshot.block(cell.x() & 15, cell.y(), cell.z() & 15)));
    }
  }

  private boolean isStale(Cached cached) {
    return clock.getAsLong() - cached.cachedAt > settings.stalenessMillis();
  }

  private CompletableFuture<MinecraftChunk> fetchLocked(
      ChunkKey key, MinecraftWorld world, boolean prefetch) {
    CompletableFuture<MinecraftChunk> pending = inFlight.get(key);
    if (pending != null) {
      return pending;
    }
    CompletableFuture<MinecraftChunk> fetch =
        platform.fetchChunk(key.chunkX, key.chunkZ, world, settings.loadPolicy());
    inFlight.put(key, fetch);
    fetch.whenComplete(
        (snapshot, error) -> {
          synchronized (lock) {
            inFlight.remove(key);
            if (error == null && snapshot != null) {
              cache.put(key, new Cached(snapshot, clock.getAsLong(), prefetch));
            }
          }
        });
    return fetch;
  }

  private void triggerReadAhead(Cell cell, MinecraftWorld world) {
    int minChunkX = (cell.x() - CHUNK_PREFETCH_MARGIN) >> 4;
    int maxChunkX = (cell.x() + CHUNK_PREFETCH_MARGIN) >> 4;
    int minChunkZ = (cell.z() - CHUNK_PREFETCH_MARGIN) >> 4;
    int maxChunkZ = (cell.z() + CHUNK_PREFETCH_MARGIN) >> 4;
    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
      for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
        if (cx == 0 && cz == 0) {
          continue;
        }
        prefetch(cx, cz, world);
      }
    }
  }

  private void prefetch(int chunkX, int chunkZ, MinecraftWorld world) {
    ChunkKey key = new ChunkKey(world.key(), chunkX, chunkZ);
    if (!cache.containsKey(key) && !inFlight.containsKey(key)) {
      fetchLocked(key, world, true);
    }
  }

  private record ChunkKey(String worldKey, int chunkX, int chunkZ) {}

  private static class Cached {
    final MinecraftChunk chunk;
    final long cachedAt;
    private boolean prefetched;

    private Cached(MinecraftChunk chunk, long cachedAt, boolean prefetched) {
      this.chunk = chunk;
      this.cachedAt = cachedAt;
      this.prefetched = prefetched;
    }

    void directlyAccessed() {
      prefetched = false;
    }
  }
}
