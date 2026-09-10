/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

/**
 * An immutable reading of the {@link ChunkProvider}'s counters, taken with {@link
 * ChunkProvider#stats()}.
 *
 * <p>The counters are provider-wide and monotonic, not per-search: one provider serves every search
 * on the server, and a block request carries no search identity. To attribute work to a single
 * search, take a reading before and after it and {@link #since(ChunkProviderStats) subtract} —
 * exact when one search runs at a time (the case worth measuring on a test server), and an upper
 * bound when several overlap.
 *
 * @param chunkRequests calls to {@link ChunkProvider#block}
 * @param cacheHits requests served from a cached snapshot without waiting
 * @param directFetches non-speculative chunk fetches issued (a request had to wait for one)
 * @param prefetches speculative read-ahead fetches issued
 * @param prefetchesUsed prefetched snapshots that a later request actually read
 * @param prefetchesWasted prefetched snapshots evicted or expired without ever being read
 * @param unknownChunks fetches that resolved to {@link MinecraftChunk.Unknown} — the load policy
 *     declined, the world was gone, or the platform refused; every block in them reads impassable
 * @param staleEvictions cached snapshots dropped for exceeding the staleness window
 * @param invalidations cached snapshots dropped because a block in them actually changed
 * @param directFetchMillis summed wall-clock latency of the direct fetches
 */
public record ChunkProviderStats(
    long chunkRequests,
    long cacheHits,
    long directFetches,
    long prefetches,
    long prefetchesUsed,
    long prefetchesWasted,
    long unknownChunks,
    long staleEvictions,
    long invalidations,
    long directFetchMillis) {

  /**
   * Returns this reading minus an earlier one, i.e. the work done between the two.
   *
   * @param earlier the reading taken first
   * @return the difference
   */
  public ChunkProviderStats since(ChunkProviderStats earlier) {
    return new ChunkProviderStats(
        chunkRequests - earlier.chunkRequests,
        cacheHits - earlier.cacheHits,
        directFetches - earlier.directFetches,
        prefetches - earlier.prefetches,
        prefetchesUsed - earlier.prefetchesUsed,
        prefetchesWasted - earlier.prefetchesWasted,
        unknownChunks - earlier.unknownChunks,
        staleEvictions - earlier.staleEvictions,
        invalidations - earlier.invalidations,
        directFetchMillis - earlier.directFetchMillis);
  }

  /** The share of block requests served without waiting, in {@code [0, 1]}. */
  public double hitRatio() {
    return chunkRequests == 0 ? 0.0 : (double) cacheHits / chunkRequests;
  }

  /** The share of read-ahead fetches that were never read, in {@code [0, 1]}. */
  public double prefetchWasteRatio() {
    long settled = prefetchesUsed + prefetchesWasted;
    return settled == 0 ? 0.0 : (double) prefetchesWasted / settled;
  }

  /** The mean latency of a direct fetch, in milliseconds. */
  public double meanDirectFetchMillis() {
    return directFetches == 0 ? 0.0 : (double) directFetchMillis / directFetches;
  }

  @Override
  public String toString() {
    return ("chunkRequests:%d, cacheHits:%d (%.1f%%), directFetches:%d (mean %.1fms), "
            + "prefetches:%d (used:%d, wasted:%d = %.1f%%), unknownChunks:%d, staleEvictions:%d, invalidations:%d")
        .formatted(
            chunkRequests,
            cacheHits,
            hitRatio() * 100,
            directFetches,
            meanDirectFetchMillis(),
            prefetches,
            prefetchesUsed,
            prefetchesWasted,
            prefetchWasteRatio() * 100,
            unknownChunks,
            staleEvictions,
            invalidations);
  }
}
