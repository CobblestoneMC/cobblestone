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
import java.util.Optional;
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

  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final LongSupplier clock;

  private final Object lock = new Object();
  private final Map<ChunkKey, Cached> cache;
  private final Map<ChunkKey, CompletableFuture<Optional<MinecraftChunk>>> inFlight =
      new HashMap<>();

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
      if (cached != null && !isStale(cached)) {
        triggerReadAhead(cell, world);
        return FutureOr.of(cached.chunk().block(cell.x() & 15, cell.y(), cell.z() & 15));
      }
      if (cached != null) {
        cache.remove(key);
      }
      CompletableFuture<Optional<MinecraftChunk>> fetch = fetchLocked(key, chunkX, chunkZ, world);
      return FutureOr.ofFuture(
          fetch.thenApply(
              snapshot ->
                  snapshot
                      .map(chunk -> chunk.block(cell.x() & 15, cell.y(), cell.z() & 15))
                      .orElse(UnknownBlock.INSTANCE)));
    }
  }

  private boolean isStale(Cached cached) {
    return clock.getAsLong() - cached.cachedAt() > settings.stalenessMillis();
  }

  private CompletableFuture<Optional<MinecraftChunk>> fetchLocked(
      ChunkKey key, int chunkX, int chunkZ, MinecraftWorld world) {
    CompletableFuture<Optional<MinecraftChunk>> pending = inFlight.get(key);
    if (pending != null) {
      return pending;
    }
    CompletableFuture<Optional<MinecraftChunk>> fetch =
        platform.fetchChunk(chunkX, chunkZ, world, settings.loadPolicy());
    inFlight.put(key, fetch);
    fetch.whenComplete(
        (snapshot, error) -> {
          synchronized (lock) {
            inFlight.remove(key);
            if (error == null && snapshot != null && snapshot.isPresent()) {
              cache.put(key, new Cached(snapshot.get(), clock.getAsLong()));
            }
          }
        });
    return fetch;
  }

  private void triggerReadAhead(Cell cell, MinecraftWorld world) {
    int margin = settings.readAheadMargin();
    if (margin <= 0) {
      return;
    }
    int localX = cell.x() & 15;
    int localZ = cell.z() & 15;
    int chunkX = cell.x() >> 4;
    int chunkZ = cell.z() >> 4;
    if (localX < margin) {
      prefetch(chunkX - 1, chunkZ, world);
    } else if (localX >= 16 - margin) {
      prefetch(chunkX + 1, chunkZ, world);
    }
    if (localZ < margin) {
      prefetch(chunkX, chunkZ - 1, world);
    } else if (localZ >= 16 - margin) {
      prefetch(chunkX, chunkZ + 1, world);
    }
  }

  private void prefetch(int chunkX, int chunkZ, MinecraftWorld world) {
    ChunkKey key = new ChunkKey(world.key(), chunkX, chunkZ);
    if (!cache.containsKey(key) && !inFlight.containsKey(key)) {
      fetchLocked(key, chunkX, chunkZ, world);
    }
  }

  private record ChunkKey(String worldKey, int chunkX, int chunkZ) {}

  private record Cached(MinecraftChunk chunk, long cachedAt) {}
}
