/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.jetbrains.annotations.Nullable;

/**
 * A small, size-bounded (LRU) cache of chunk snapshots belonging to <b>one solve</b>. A block from
 * a cached chunk is served immediately (a cache hit); a miss triggers a single de-duplicated fetch
 * and is served as a pending {@link FutureOr}, and every newly requested block reads ahead along
 * the column of chunks between it and the destination, so that the chunk a solve wants next is
 * usually already in hand.
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
 * <p>Read-ahead is directional on purpose: a solve advances towards its destination, so chunks
 * behind it are work that would almost never be used. See {@link #triggerReadAhead}.
 *
 * <p>A world implementation delegates {@link MinecraftWorld#blockAt(Cell, Cell)} to {@link
 * #block(Cell, MinecraftWorld, Cell)}. It is still internally synchronized — a fetch completes on
 * whatever thread the platform finished its IO on, and the solve reads from its worker — but the
 * lock is now uncontended in the ordinary case, since only one solve can reach it.
 */
public final class ChunkProvider {

  /**
   * Lateral half-width, in blocks, of the prefetched column. Most modes read a block or so to
   * either side of the cell they are expanding from, so a five-block-wide column is enough of a
   * buffer to have what they ask for next without dragging in chunks they will never touch.
   */
  private static final int PREFETCH_LATERAL_RADIUS = 2;

  private final PlatformApi<?> platform;
  private final ChunkProviderSettings settings;
  private final LongSupplier clock;

  private final Object lock = new Object();
  private final Map<ChunkKey, Cached> cache;
  private final Map<ChunkKey, CompletableFuture<MinecraftChunk>> inFlight = new HashMap<>();

  /**
   * Chunks a read-ahead asked for and got {@link MinecraftChunk.Unknown} back from.
   *
   * <p>An unknown answer to a speculative request is not cached — it may say only that the platform
   * declined the work, and caching it would wall the solve off from a chunk it might really need.
   * But it must not be asked for speculatively again either: read-ahead fires on every cache miss,
   * and the columns of nearby cells overlap, so without this the same chunk is re-read once for
   * every chunk the solve crosses behind it — disk reads for a chunk nothing is waiting on.
   *
   * <p>Remembering that we asked is weaker than remembering the answer, which is the point: a solve
   * that actually reaches this chunk still fetches it for real.
   */
  private final Set<ChunkKey> refusedReadAhead = new HashSet<>();

  // Counters for ChunkProviderStats, all read and written under `lock`.
  private long chunkLookups;
  private long cacheHits;
  private long directFetches;
  private long prefetches;
  private long prefetchesUsed;
  private long prefetchesWasted;
  private long unknownChunks;
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
      refusedReadAhead.remove(key); // asked for real now; whatever comes back is the answer
    }
    long startedAt = clock.getAsLong();
    CompletableFuture<MinecraftChunk> fetch =
        platform
            .fetchChunk(key.chunkX, key.chunkZ, world, settings.loadPolicy(), !prefetch)
            // A failure here means the platform could not source the chunk at all — not that its
            // fast path missed, which platforms handle themselves by falling back. There is
            // nothing left to try, so this is exactly the case Unknown already describes: the
            // solve treats the chunk as a wall rather than failing with it.
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
                // A platform may decline speculative work it would still do for a solve actually
                // blocked on the chunk, so a read-ahead's unknown says nothing about the world —
                // it is not cached, only noted, so that read-ahead stops re-asking.
                refusedReadAhead.add(key);
                return;
              }
              // A direct fetch's unknown is cached like anything else: within one solve, a chunk
              // that could not be sourced a moment ago will not have become sourceable, and the
              // alternative is re-reading it for every cell of a frontier pressed against it.
            }
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
    if (settings.prefetchDistance() <= 0) {
      return; // read-ahead switched off
    }
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
    if (!cache.containsKey(key) && !inFlight.containsKey(key) && !refusedReadAhead.contains(key)) {
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
