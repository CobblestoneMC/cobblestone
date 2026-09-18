/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.jetbrains.annotations.Nullable;

/**
 * A thread-safe, size-bounded (LRU) cache of chunk snapshots, sitting between modes and the
 * platform. A block from a cached chunk is served immediately (a cache hit); a miss triggers a
 * single de-duplicated fetch and is served as a pending {@link FutureOr}, and every newly requested
 * block reads ahead along the column of chunks between it and the destination, so that the chunk a
 * search wants next is usually already in hand.
 *
 * <p><b>Freshness</b> is not checked per read: a read happens tens of millions of times per search,
 * and re-fetching mid-solve would cost more than a stale block is worth. Three other things keep
 * snapshots honest instead. A snapshot past the staleness window is dropped when the cache next
 * takes an entry; {@link #invalidate} drops one the moment a block in it changes; and a periodic
 * sweep drops any snapshot older than {@link #MAX_SNAPSHOT_AGE_MILLIS}, which is the backstop for
 * an edit that fires no block-change event at all.
 *
 * <p>The one entry read-freshness applies to is an {@linkplain MinecraftChunk.Unknown unknown}
 * chunk: see {@link #UNKNOWN_RETRY_MILLIS}.
 *
 * <p>Read-ahead is directional on purpose: chunk loading is the throughput bottleneck, and a search
 * advances towards its destination, so chunks behind it are work that would almost never be used.
 * See {@link #triggerReadAhead}.
 *
 * <p>A world implementation delegates {@link MinecraftWorld#blockAt(Cell, Cell)} to {@link
 * #block(Cell, MinecraftWorld, Cell)}.
 */
public final class ChunkProvider {

  /**
   * Lateral half-width, in blocks, of the prefetched column. Most modes read a block or so to
   * either side of the cell they are expanding from, so a five-block-wide column is enough of a
   * buffer to have what they ask for next without dragging in chunks they will never touch.
   */
  private static final int PREFETCH_LATERAL_RADIUS = 2;

  /**
   * How long a cached {@link MinecraftChunk.Unknown} answer is trusted before the platform is asked
   * again.
   *
   * <p>Unknown is not only "this chunk does not exist": a platform also returns it for a chunk that
   * is merely not loaded yet, whose existence scan has not finished, or whose load timed out. Those
   * answers turn into terrain a moment later, and no block-change event fires for a chunk nobody
   * has touched — so an unknown entry that never expired would wall a search off from real ground
   * for as long as the LRU kept it. A short window is still enough to collapse the storm of repeat
   * reads a search pressed up against ungenerated terrain makes of the same absent chunk.
   */
  private static final long UNKNOWN_RETRY_MILLIS = 1_000L;

  /**
   * The oldest a cached snapshot may get before it is dropped regardless of what is using it.
   *
   * <p>Neither of the other two paths can bound this. Insert-time eviction only ever inspects the
   * eldest entry, and with access ordering a chunk a search keeps touching is never the eldest, so
   * it never expires the very chunks a search walks through — and expires nothing at all while no
   * new chunk is being fetched. Block-change eviction is prompt but only sees what fires a Bukkit
   * event, which leaves out {@code /fill} and {@code /setblock}, world-editing plugins with block
   * events off, a regenerated chunk, and anything written straight through the server internals.
   *
   * <p>Deliberately far longer than {@link ChunkProviderSettings#stalenessMillis()}: that window
   * governs entries nothing is using, while this one is a backstop against an edit nothing told us
   * about, and enforcing the short window here would have a long search re-loading its own working
   * set several times over, stalling for a fetch latency each time.
   */
  static final long MAX_SNAPSHOT_AGE_MILLIS = 120_000L;

  /**
   * How often the cache is swept for snapshots past {@link #MAX_SNAPSHOT_AGE_MILLIS}. The sweep
   * walks at most {@link ChunkProviderSettings#maxCachedChunks()} entries, seconds apart, under a
   * lock every cache hit already takes.
   */
  private static final long STALE_SWEEP_MILLIS = 10_000L;

  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final LongSupplier clock;

  private final Object lock = new Object();
  private final Map<ChunkKey, Cached> cache;
  private final Map<ChunkKey, CompletableFuture<MinecraftChunk>> inFlight = new HashMap<>();

  /**
   * The keys currently in {@link #cache}, mirrored so {@link #invalidate} can tell "nothing cached"
   * without taking {@link #lock}.
   *
   * <p>Every block change on the server invalidates, on the thread that owns the block, while the
   * lock is held by every chunk lookup a running search makes — tens of thousands a second. Almost
   * all of those changes are in chunks nothing has ever cached, and this lets those cost a hash
   * lookup instead of a wait on a monitor whose hold time scales with how many searches are
   * running. It may trail the cache by an instant, which only ever costs a needless lock.
   */
  private final Set<ChunkKey> cachedKeys = ConcurrentHashMap.newKeySet();

  // Counters for ChunkProviderStats. All are read and written under `lock` except the two the
  // fetch callback touches, which take the lock themselves.
  private long chunkLookups;
  private long cacheHits;
  private long directFetches;
  private long prefetches;
  private long prefetchesUsed;
  private long prefetchesWasted;
  private long unknownChunks;
  private long staleEvictions;
  private long invalidations;
  private long directFetchMillis;
  private long lastSweepAt;

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
    this.lastSweepAt = clock.getAsLong();
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<ChunkKey, Cached> eldest) {
            // Staleness is checked here, on insert, and never on a read: a read happens tens of
            // millions of times per search, an insert once per chunk fetched, and the window is
            // shorter than a search — so reading the clock per read would both cost the call and
            // re-fetch chunks the running search is still using.
            boolean stale = isStale(eldest.getValue());
            if (size() <= settings.maxCachedChunks() && !stale) {
              return false;
            }
            if (stale) {
              staleEvictions++;
            }
            if (eldest.getValue().prefetched) {
              prefetchesWasted++; // read ahead for, then evicted before anything read it
            }
            cachedKeys.remove(eldest.getKey());
            return true;
          }
        };
  }

  /**
   * Drops the cached snapshot of one chunk, so the next request re-reads it from the platform.
   *
   * <p>This is how a world edit reaches a running server: freshness is otherwise only checked when
   * the cache takes a new entry, and a chunk a search keeps touching is never the eldest, so a
   * block broken in it would go unnoticed until a restart. Safe to call from any thread — on Folia
   * these arrive on region threads.
   *
   * @param worldKey the world's namespaced key
   * @param chunkX the chunk X coordinate
   * @param chunkZ the chunk Z coordinate
   * @return whether a snapshot was actually dropped
   */
  public boolean invalidate(String worldKey, int chunkX, int chunkZ) {
    ChunkKey key = new ChunkKey(worldKey, chunkX, chunkZ);
    if (!cachedKeys.contains(key)) {
      return false; // nothing cached here; do not queue behind the searches holding the lock
    }
    synchronized (lock) {
      if (cache.remove(key) == null) {
        return false;
      }
      cachedKeys.remove(key);
      invalidations++;
      return true;
    }
  }

  /**
   * Drops the cached snapshot of the chunk containing a block position.
   *
   * @param worldKey the world's namespaced key
   * @param blockX the block X coordinate
   * @param blockZ the block Z coordinate
   * @return whether a snapshot was actually dropped
   */
  public boolean invalidateBlock(String worldKey, int blockX, int blockZ) {
    return invalidate(worldKey, blockX >> 4, blockZ >> 4);
  }

  /**
   * Waits for the chunk fetches already issued to settle, so shutdown does not walk away from IO
   * the server is still doing on Cobblestone's behalf.
   *
   * <p>This is the whole of what has to be waited for. A search parked on a chunk holds no claim on
   * it — cancelling a search abandons its pending values and never looks at them again — so the
   * thing still owed is the fetch, and {@link #inFlight} is already an exact register of those: one
   * entry per fetch, added when it is issued and removed when it settles. Nothing else needs to
   * keep a list.
   *
   * <p>Bounded, because it cannot be trusted absolutely: a platform whose IO threads are already
   * gone may never complete what it promised, and a shutdown that hangs on that is worse than one
   * that gives up on a read. Call {@link PlatformApi#shutdown()} first so that most of what is
   * outstanding is cancelled rather than waited for.
   *
   * @param timeoutMillis how long to wait before giving up
   * @return {@code true} if everything settled, {@code false} on timeout or interruption
   */
  public boolean awaitInFlight(long timeoutMillis) {
    CompletableFuture<?>[] pending;
    synchronized (lock) {
      // Snapshot and release: each fetch removes itself from inFlight under this same lock when it
      // completes, so waiting while holding it would deadlock against the completion it waits for.
      pending = inFlight.values().toArray(new CompletableFuture<?>[0]);
    }
    if (pending.length == 0) {
      return true;
    }
    try {
      CompletableFuture.allOf(pending).get(timeoutMillis, TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (TimeoutException | ExecutionException failed) {
      // A failed fetch is still a settled one; only the timeout is a real answer of "no".
      return failed instanceof ExecutionException;
    }
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
          staleEvictions,
          invalidations,
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
      sweepAged();
      Cached cached = cache.get(key);
      if (cached != null && cached.chunk == MinecraftChunk.Unknown.INSTANCE && expired(cached)) {
        cache.remove(key); // stop answering from a "not loaded yet" that may have become terrain
        cachedKeys.remove(key);
        cached = null;
      }

      // trigger read-ahead around this cell if we have not seen this chunk requested
      // or if we have only requested it because it was part of another prefetch
      if (cached == null) {
        triggerReadAhead(cell, world, destination);
      } else {
        if (cached.prefetched) {
          triggerReadAhead(cell, world, destination);
          prefetchesUsed++; // the read-ahead paid off: this is its first real read
        }
        cached.directlyAccessed();
        cacheHits++;
        return FutureOr.of(cached.chunk);
      }
      return FutureOr.ofFuture(fetchLocked(key, world, false));
    }
  }

  private boolean isStale(Cached cached) {
    return clock.getAsLong() - cached.cachedAt > settings.stalenessMillis();
  }

  /**
   * Drops every snapshot older than {@link #MAX_SNAPSHOT_AGE_MILLIS}, at most once per {@link
   * #STALE_SWEEP_MILLIS}. Called under {@link #lock}.
   */
  private void sweepAged() {
    long now = clock.getAsLong();
    if (now - lastSweepAt < STALE_SWEEP_MILLIS) {
      return;
    }
    lastSweepAt = now;
    Iterator<Map.Entry<ChunkKey, Cached>> entries = cache.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<ChunkKey, Cached> entry = entries.next();
      if (now - entry.getValue().cachedAt <= MAX_SNAPSHOT_AGE_MILLIS) {
        continue;
      }
      entries.remove();
      cachedKeys.remove(entry.getKey());
      staleEvictions++;
      if (entry.getValue().prefetched) {
        prefetchesWasted++;
      }
    }
  }

  /** Whether an unknown-chunk answer has outlived {@link #UNKNOWN_RETRY_MILLIS}. */
  private boolean expired(Cached cached) {
    return clock.getAsLong() - cached.cachedAt > UNKNOWN_RETRY_MILLIS;
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
            // A failure here means the platform could not source the chunk at all — not that its
            // fast path missed, which platforms handle themselves by falling back. There is
            // nothing left to try, so this is exactly the case Unknown already describes: the
            // search treats the chunk as a wall rather than failing with it. Cached like any
            // unknown, which is to say for UNKNOWN_RETRY_MILLIS — long enough to collapse a storm
            // of repeat reads against a chunk that will not load, short enough that a failure
            // which was only transient is asked about again a second later.
            .exceptionally(error -> MinecraftChunk.Unknown.INSTANCE);
    inFlight.put(key, fetch);
    fetch.whenComplete(
        (snapshot, error) -> {
          synchronized (lock) {
            inFlight.remove(key);
            if (!prefetch) {
              directFetchMillis += clock.getAsLong() - startedAt;
            }
            if (error != null || snapshot == null) {
              return;
            }
            if (snapshot == MinecraftChunk.Unknown.INSTANCE) {
              unknownChunks++;
              if (prefetch) {
                // A platform may decline speculative work it would still do for a search actually
                // blocked on the chunk, so a read-ahead's unknown says nothing about the world.
                return;
              }
              // A direct fetch's unknown is cached, but only briefly; see UNKNOWN_RETRY_MILLIS.
            }
            // Mirror first: the put may evict, and eviction is what removes from the mirror.
            cachedKeys.add(key);
            cache.put(key, new Cached(snapshot, clock.getAsLong(), prefetch));
          }
        });
    return fetch;
  }

  /**
   * Prefetches the chunks a search is about to want: those intersecting a column of radius {@link
   * #PREFETCH_LATERAL_RADIUS} running from {@code cell} towards {@code destination}, as far as the
   * configured {@link ChunkProviderSettings#prefetchDistance()} (or as far as the destination, if
   * it is nearer).
   *
   * <p>The column starts a lateral radius <em>behind</em> the cell so that a cell sitting right on
   * a chunk border still pulls in the chunk it just came out of, whose blocks its own expansion may
   * read. With no destination to aim at, the column degenerates to a disc around the cell: enough
   * to cover a border cell's neighbors, and nothing speculative beyond that.
   */
  private void triggerReadAhead(Cell cell, MinecraftWorld world, @Nullable Cell destination) {
    double radius = PREFETCH_LATERAL_RADIUS;
    // Block centers: a cell's coordinates name the lower corner of a unit cube.
    double startX = cell.x() + 0.5;
    double startZ = cell.z() + 0.5;
    double endX = startX;
    double endZ = startZ;
    if (destination != null) {
      double deltaX = destination.x() - cell.x();
      double deltaZ = destination.z() - cell.z();
      double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
      if (distance > 1e-6) {
        double unitX = deltaX / distance;
        double unitZ = deltaZ / distance;
        double reach = Math.min(distance, settings.prefetchDistance());
        endX = startX + unitX * reach;
        endZ = startZ + unitZ * reach;
        startX -= unitX * radius;
        startZ -= unitZ * radius;
      }
    }

    int centerChunkX = cell.x() >> 4;
    int centerChunkZ = cell.z() >> 4;
    int minChunkX = Math.floorDiv((int) Math.floor(Math.min(startX, endX) - radius), 16);
    int maxChunkX = Math.floorDiv((int) Math.floor(Math.max(startX, endX) + radius), 16);
    int minChunkZ = Math.floorDiv((int) Math.floor(Math.min(startZ, endZ) - radius), 16);
    int maxChunkZ = Math.floorDiv((int) Math.floor(Math.max(startZ, endZ) + radius), 16);
    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
      for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
        if (cx == centerChunkX && cz == centerChunkZ) {
          continue; // the caller fetches the cell's own chunk itself, as a direct (urgent) fetch
        }
        if (columnTouchesChunk(startX, startZ, endX, endZ, radius, cx, cz)) {
          prefetch(cx, cz, world);
        }
      }
    }
  }

  /**
   * Returns whether the column — every point within {@code radius} of the segment from ({@code
   * startX}, {@code startZ}) to ({@code endX}, {@code endZ}) — reaches into the given chunk's
   * square footprint.
   */
  private static boolean columnTouchesChunk(
      double startX, double startZ, double endX, double endZ, double radius, int cx, int cz) {
    double minX = cx << 4;
    double minZ = cz << 4;
    double maxX = minX + 16;
    double maxZ = minZ + 16;
    if (segmentTouchesBox(startX, startZ, endX, endZ, minX, minZ, maxX, maxZ)) {
      return true;
    }
    // The segment misses the square, so the two are disjoint convex shapes and their nearest pair
    // of points involves a vertex of one of them: measuring both segment ends against the square
    // and all four corners against the segment covers every case.
    double radiusSquared = radius * radius;
    if (pointToBoxSquared(startX, startZ, minX, minZ, maxX, maxZ) <= radiusSquared
        || pointToBoxSquared(endX, endZ, minX, minZ, maxX, maxZ) <= radiusSquared) {
      return true;
    }
    return pointToSegmentSquared(minX, minZ, startX, startZ, endX, endZ) <= radiusSquared
        || pointToSegmentSquared(maxX, minZ, startX, startZ, endX, endZ) <= radiusSquared
        || pointToSegmentSquared(minX, maxZ, startX, startZ, endX, endZ) <= radiusSquared
        || pointToSegmentSquared(maxX, maxZ, startX, startZ, endX, endZ) <= radiusSquared;
  }

  /** Liang-Barsky: whether a segment enters an axis-aligned box at all. */
  private static boolean segmentTouchesBox(
      double startX,
      double startZ,
      double endX,
      double endZ,
      double minX,
      double minZ,
      double maxX,
      double maxZ) {
    double deltaX = endX - startX;
    double deltaZ = endZ - startZ;
    double[] edgeDirections = {-deltaX, deltaX, -deltaZ, deltaZ};
    double[] edgeDistances = {startX - minX, maxX - startX, startZ - minZ, maxZ - startZ};
    double enter = 0;
    double exit = 1;
    for (int i = 0; i < edgeDirections.length; i++) {
      double direction = edgeDirections[i];
      double distance = edgeDistances[i];
      if (direction == 0) {
        if (distance < 0) {
          return false; // parallel to this edge, and wholly outside it
        }
        continue;
      }
      double t = distance / direction;
      if (direction < 0) {
        enter = Math.max(enter, t);
      } else {
        exit = Math.min(exit, t);
      }
      if (enter > exit) {
        return false;
      }
    }
    return true;
  }

  /** The squared distance from a point to the nearest point of an axis-aligned box. */
  private static double pointToBoxSquared(
      double x, double z, double minX, double minZ, double maxX, double maxZ) {
    double dx = Math.max(0, Math.max(minX - x, x - maxX));
    double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
    return dx * dx + dz * dz;
  }

  /** The squared distance from a point to the nearest point of a segment. */
  private static double pointToSegmentSquared(
      double x, double z, double startX, double startZ, double endX, double endZ) {
    double deltaX = endX - startX;
    double deltaZ = endZ - startZ;
    double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
    double t = 0;
    if (lengthSquared > 0) {
      double projection = ((x - startX) * deltaX + (z - startZ) * deltaZ) / lengthSquared;
      t = Math.max(0, Math.min(1, projection));
    }
    double dx = x - (startX + t * deltaX);
    double dz = z - (startZ + t * deltaZ);
    return dx * dx + dz * dz;
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
