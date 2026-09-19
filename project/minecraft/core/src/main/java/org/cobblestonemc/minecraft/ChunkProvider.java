/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.jetbrains.annotations.Nullable;

/**
 * A small, size-bounded (LRU) cache of chunk snapshots belonging to <b>one solve</b>. A block from
 * a cached chunk is served immediately (a cache hit); a miss triggers a single de-duplicated fetch
 * and is served as a pending {@link FutureOr}, and every newly requested block reads ahead along
 * the column of chunks between it and the destination (see {@link ReadAheadColumn}), so that the
 * chunk a solve wants next is usually already in hand.
 *
 * <p><b>One per solve, and nothing is remembered past it.</b> This used to be a single large cache
 * shared by every search on the server, which made sense when a miss meant loading a chunk through
 * the chunk system: the snapshot was expensive enough to be worth keeping, worth invalidating when
 * a block changed, and worth expiring on a timer. Reading a chunk off disk is cheap enough that
 * none of that pays for itself, and a shared cache had a real cost of its own — several players
 * searching in different places evicted each other's working sets. So each solve now gets its own,
 * sized to its frontier, and it is dropped when the solve ends.
 *
 * <p>That also settles freshness by not having the problem: a cached snapshot can only be as old as
 * the solve holding it, which is seconds. A block broken mid-solve may be missed, and is not worth
 * a lock on every read to catch.
 *
 * <p><b>Failures.</b> A fetch that fails reads as {@link MinecraftChunk.Unknown} — a wall — to
 * whoever was waiting on it. A {@linkplain ChunkFetch.Failed#permanent() permanent} failure is
 * cached like any other answer, since asking again would get the same one. A {@linkplain
 * ChunkFetch.Failed#transientFailure() transient} failure is not, at first: the next request for
 * that chunk fetches it again. After {@link ChunkProviderSettings#maxFetchAttempts()} of them the
 * provider stops asking and caches the wall, so that a solve pressed against a chunk that keeps
 * failing routes around it rather than re-reading it for every cell of its frontier.
 *
 * <p>A world implementation delegates {@link MinecraftWorld#blockAt(Cell, Cell)} to {@link
 * #block(Cell, MinecraftWorld, Cell)}. It is still internally synchronized — a fetch completes on
 * whatever thread the platform finished its IO on, and the solve reads from its worker — but the
 * lock is uncontended in the ordinary case, since only one solve can reach it.
 */
public final class ChunkProvider {

  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final LongSupplier clock;

  private final Object lock = new Object();
  private final Map<ChunkKey, Cached> cache;
  private final Map<ChunkKey, CompletableFuture<MinecraftChunk>> inFlight = new HashMap<>();

  /** Transient failures so far, by chunk, for chunks not yet given up on or obtained. */
  private final Map<ChunkKey, Integer> transientFailuresByChunk = new HashMap<>();

  // Counters for ChunkProviderStats, all read and written under `lock`.
  private long chunkLookups;
  private long cacheHits;
  private long directFetches;
  private long prefetches;
  private long prefetchesUsed;
  private long prefetchesWasted;
  private long unknownChunks;
  private long transientFailures;
  private long directFetchMillis;

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
            if (size() <= settings.maxCachedChunks()) {
              return false;
            }
            if (eldest.getValue().prefetched) {
              prefetchesWasted++; // read ahead for, then evicted before anything read it
            }
            return true;
          }
        };
  }

  /**
   * Returns a reading of the provider-wide counters. Diff two readings to attribute work to one
   * search; see {@link ChunkProviderStats}.
   *
   * @return the current counters
   */
  public ChunkProviderStats stats() {
    synchronized (lock) {
      return new ChunkProviderStats(
          chunkLookups,
          cacheHits,
          directFetches,
          prefetches,
          prefetchesUsed,
          prefetchesWasted,
          unknownChunks,
          transientFailures,
          directFetchMillis);
    }
  }

  /**
   * Returns the block at {@code cell} in {@code world}, immediate on a cache hit or pending on a
   * miss. Cells outside the world's vertical bounds resolve to an impassable block.
   *
   * @param cell the cell
   * @param world the world
   * @param destination the destination of the calling process
   * @return the block, immediate or pending
   */
  public FutureOr<MinecraftBlock> block(Cell cell, MinecraftWorld world, Cell destination) {
    if (cell.y() < world.minY() || cell.y() > world.maxY()) {
      return FutureOr.of(UnknownBlock.INSTANCE);
    }
    return chunk(cell, world, destination)
        .map(snapshot -> snapshot.block(cell.x() & 15, cell.y(), cell.z() & 15));
  }

  /**
   * Returns the snapshot of the chunk containing {@code cell}, immediate on a cache hit or pending
   * on a miss — the same path {@link #block} takes, without resolving a single block.
   *
   * <p>Callers that need many blocks around one point should use this and index the snapshot
   * directly. A mode's neighborhood is a couple of hundred cells spread over about four chunks, so
   * resolving it a cell at a time costs a key allocation, this provider's monitor, and an
   * access-ordered map relink <i>per block</i> — tens of millions of times per search.
   *
   * @param cell a cell in the wanted chunk
   * @param world the world
   * @param destination the destination of the calling process, for read-ahead
   * @return the snapshot, immediate or pending
   */
  public FutureOr<MinecraftChunk> chunk(Cell cell, MinecraftWorld world, Cell destination) {
    int chunkX = cell.x() >> 4;
    int chunkZ = cell.z() >> 4;
    ChunkKey key = new ChunkKey(world.key(), chunkX, chunkZ);
    synchronized (lock) {
      chunkLookups++;
      Cached cached = cache.get(key);

      // trigger read-ahead around this cell if we have not seen this chunk requested
      // or if we have only requested it because it was part of another prefetch
      if (cached == null) {
        // first fetch needed chunk before readahead for priority
        var future = FutureOr.ofFuture(fetchLocked(key, world, false));
        triggerReadAhead(cell, world, destination);
        return future;
      } else {
        if (cached.prefetched) {
          triggerReadAhead(cell, world, destination);
          prefetchesUsed++; // the read-ahead paid off: this is its first real read
        }
        cached.directlyAccessed();
        cacheHits++;
        return FutureOr.of(cached.chunk);
      }
    }
  }

  private CompletableFuture<MinecraftChunk> fetchLocked(
      ChunkKey key, MinecraftWorld world, boolean prefetch) {
    CompletableFuture<MinecraftChunk> pending = inFlight.get(key);
    if (pending != null) {
      return pending;
    }
    if (prefetch) {
      prefetches++;
    } else {
      directFetches++;
    }
    long startedAt = clock.getAsLong();
    CompletableFuture<MinecraftChunk> fetch =
        platform
            .fetchChunk(key.chunkX, key.chunkZ, world, settings.loadPolicy(), !prefetch)
            // A platform is meant to fold its own failures into a Failed answer. One that throws
            // anyway has said nothing about the chunk, so it counts as the kind worth retrying.
            .exceptionally(error -> ChunkFetch.Failed.transientFailure())
            .thenApply(
                outcome -> {
                  // Settled before anyone waiting on the fetch sees it, so a caller that reacts by
                  // asking for the chunk again finds the cache already up to date.
                  synchronized (lock) {
                    inFlight.remove(key);
                    if (!prefetch) {
                      directFetchMillis += clock.getAsLong() - startedAt;
                    }
                    settleLocked(key, outcome, prefetch);
                  }
                  return outcome.chunkOrUnknown();
                });
    if (!fetch.isDone()) {
      // A fetch that completed synchronously has settled already; registering it now would leave
      // an entry nothing will remove.
      inFlight.put(key, fetch);
    }
    return fetch;
  }

  /** Records what a fetch came back with: cached, or counted towards giving up on the chunk. */
  private void settleLocked(ChunkKey key, ChunkFetch outcome, boolean prefetch) {
    if (outcome instanceof ChunkFetch.Failed failed) {
      if (failed.isTransient()) {
        transientFailures++;
        int failures = transientFailuresByChunk.merge(key, 1, Integer::sum);
        if (failures < settings.maxFetchAttempts()) {
          return; // not cached: the next request for this chunk asks again
        }
      }
      unknownChunks++;
    }
    transientFailuresByChunk.remove(key);
    cache.put(key, new Cached(outcome.chunkOrUnknown(), clock.getAsLong(), prefetch));
  }

  /** Prefetches the chunks the solve is about to want; see {@link ReadAheadColumn}. */
  private void triggerReadAhead(Cell cell, MinecraftWorld world, @Nullable Cell destination) {
    if (settings.prefetchDistance() <= 0) {
      return; // read-ahead switched off
    }
    ReadAheadColumn.forEachChunk(
        cell,
        destination,
        settings.prefetchDistance(),
        (chunkX, chunkZ) -> prefetch(chunkX, chunkZ, world));
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
