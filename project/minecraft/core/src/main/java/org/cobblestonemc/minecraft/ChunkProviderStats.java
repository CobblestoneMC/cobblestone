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
 * @param chunkLookups calls to {@link ChunkProvider#chunk} — once per chunk per caller that wants
 *     blocks in it, <b>not</b> once per block: a mode resolves its whole neighborhood from a
 *     handful of snapshots
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
    long chunkLookups,
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
        delta(chunkLookups, earlier.chunkLookups),
        delta(cacheHits, earlier.cacheHits),
        delta(directFetches, earlier.directFetches),
        delta(prefetches, earlier.prefetches),
        delta(prefetchesUsed, earlier.prefetchesUsed),
        delta(prefetchesWasted, earlier.prefetchesWasted),
        delta(unknownChunks, earlier.unknownChunks),
        delta(staleEvictions, earlier.staleEvictions),
        delta(invalidations, earlier.invalidations),
        delta(directFetchMillis, earlier.directFetchMillis));
  }

  /** One counter's delta, clamped at zero so two readings taken out of order read as no work. */
  private static long delta(long later, long earlier) {
    return Math.max(0, later - earlier);
  }

  /** The share of chunk lookups served without waiting, in {@code [0, 1]}. */
  public double hitRatio() {
    return chunkLookups == 0 ? 0.0 : (double) cacheHits / chunkLookups;
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
    return ("chunkLookups:%d, cacheHits:%d (%.1f%%), directFetches:%d (mean %.1fms), "
            + "prefetches:%d (used:%d, wasted:%d = %.1f%%), unknownChunks:%d, staleEvictions:%d, invalidations:%d")
        .formatted(
            chunkLookups,
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
