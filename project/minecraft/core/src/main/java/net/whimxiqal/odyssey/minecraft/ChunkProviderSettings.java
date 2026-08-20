/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

/**
 * Tunables for the {@link ChunkProvider}: cache capacity, snapshot staleness, the read-ahead
 * distance, and the load policy.
 *
 * @param maxCachedChunks LRU capacity, in chunk snapshots
 * @param stalenessMillis a snapshot older than this (on access) is discarded and re-fetched
 * @param prefetchDistance how far ahead of a served block, in blocks along the line towards the
 *     destination, chunks are prefetched
 * @param loadPolicy how aggressively to materialize missing chunks
 */
public record ChunkProviderSettings(
    int maxCachedChunks, long stalenessMillis, int prefetchDistance, ChunkLoadPolicy loadPolicy) {

  /** Returns settings with sensible defaults. */
  public static ChunkProviderSettings defaults() {
    return defaults(ChunkLoadPolicy.ALLOW_LOAD);
  }

  /**
   * Returns settings with sensible cache defaults and the given load policy.
   *
   * <p>The cache tunables are deliberately not configurable: the caching model is due to change
   * (fixed-height columns and time-based staleness give way to smaller cubes evicted on block
   * change), and settings admins tune now would not survive it. The load policy is different — it
   * decides how much work Odyssey may ask the server for, so it is theirs to set.
   *
   * @param loadPolicy how aggressively to materialize missing chunks
   * @return the settings
   */
  public static ChunkProviderSettings defaults(ChunkLoadPolicy loadPolicy) {
    return new ChunkProviderSettings(1024, 10_000L, 32, loadPolicy);
  }
}
