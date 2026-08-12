/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

/**
 * Tunables for the {@link ChunkProvider}: cache capacity, snapshot staleness, the read-ahead
 * margin, and the load policy.
 *
 * @param maxCachedChunks LRU capacity, in chunk snapshots
 * @param stalenessMillis a snapshot older than this (on access) is discarded and re-fetched
 * @param readAheadMargin when a served block is within this many blocks of a chunk border, the
 *     adjacent chunk in that direction is prefetched
 * @param loadPolicy how aggressively to materialize missing chunks
 */
public record ChunkProviderSettings(
    int maxCachedChunks, long stalenessMillis, ChunkLoadPolicy loadPolicy) {

  /** Returns settings with sensible defaults. */
  public static ChunkProviderSettings defaults() {
    return new ChunkProviderSettings(1024, 10_000L, ChunkLoadPolicy.LOAD_FROM_DISK);
  }
}
