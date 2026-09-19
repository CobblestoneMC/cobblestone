/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

/**
 * Tunables for the {@link ChunkProvider}: cache capacity, the read-ahead distance, and the load
 * policy.
 *
 * @param maxCachedChunks LRU capacity, in chunk snapshots, <b>per solve</b>
 * @param prefetchDistance how far ahead of a served block, in blocks along the line towards the
 *     destination, chunks are prefetched; {@code 0} disables read-ahead entirely
 * @param loadPolicy how aggressively to materialize missing chunks
 */
public record ChunkProviderSettings(
    int maxCachedChunks, int prefetchDistance, ChunkLoadPolicy loadPolicy) {

  /**
   * Default LRU capacity, per solve.
   *
   * <p>Small on purpose. The cache no longer exists to remember the world — reading a chunk off
   * disk is now cheap enough that remembering one past the search that wanted it buys nothing — it
   * exists to stop a single solve from going back to disk for the same chunk over and over as its
   * frontier works across a chunk border. A frontier touches a handful of chunks at a time, so a
   * few dozen covers the working set with room to spare.
   */
  public static final int DEFAULT_MAX_CACHED_CHUNKS = 32;

  /**
   * Default read-ahead distance, in blocks.
   *
   * <p>One or two chunks ahead. Read-ahead earned a long reach when a miss meant loading a chunk
   * through the server and waiting a tick or more for it; against a disk read it only has to cover
   * the moment between a frontier approaching a chunk border and crossing it.
   */
  public static final int DEFAULT_PREFETCH_DISTANCE = 16;

  /**
   * Returns settings with sensible defaults.
   *
   * @return the default settings
   */
  public static ChunkProviderSettings defaults() {
    return defaults(ChunkLoadPolicy.ALLOW_LOAD);
  }

  /**
   * Returns settings with default cache tunables and the given load policy.
   *
   * @param loadPolicy how aggressively to materialize missing chunks
   * @return the settings
   */
  public static ChunkProviderSettings defaults(ChunkLoadPolicy loadPolicy) {
    return new ChunkProviderSettings(
        DEFAULT_MAX_CACHED_CHUNKS, DEFAULT_PREFETCH_DISTANCE, loadPolicy);
  }
}
