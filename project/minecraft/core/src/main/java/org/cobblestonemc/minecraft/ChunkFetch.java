/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.Objects;

/**
 * The outcome of asking a platform for a chunk: the chunk itself, or the reason there isn't one.
 *
 * <p>The distinction that matters to a caller is whether asking again could help. A {@link
 * Failed#permanent() permanent} failure is a fact about the world as this policy sees it — the
 * chunk was never generated, the policy forbids loading it, the world is gone — and re-asking would
 * get the same answer. A {@link Failed#transientFailure() transient} one is a fact about this
 * attempt only — a read that errored, a load that timed out, a save that has not reached disk yet —
 * and the same question a moment later may well succeed.
 *
 * <p>Either kind reads as {@link MinecraftChunk.Unknown} to a search. What differs is how long the
 * {@link ChunkProvider} believes it.
 */
public sealed interface ChunkFetch {

  /**
   * A chunk was obtained.
   *
   * @param chunk the chunk; never {@link MinecraftChunk.Unknown}, which is what {@link Failed} is
   *     for
   */
  record Success(MinecraftChunk chunk) implements ChunkFetch {

    public Success {
      Objects.requireNonNull(chunk, "chunk");
      if (chunk == MinecraftChunk.Unknown.INSTANCE) {
        throw new IllegalArgumentException("an unknown chunk is a failure, not a success");
      }
    }

    @Override
    public MinecraftChunk chunkOrUnknown() {
      return chunk;
    }
  }

  /**
   * No chunk was obtained.
   *
   * @param isTransient whether the same request might succeed if made again
   */
  record Failed(boolean isTransient) implements ChunkFetch {

    private static final Failed PERMANENT = new Failed(false);
    private static final Failed TRANSIENT = new Failed(true);

    /**
     * Returns the failure that asking again will not fix.
     *
     * @return the permanent failure
     */
    public static Failed permanent() {
      return PERMANENT;
    }

    /**
     * Returns the failure that asking again might fix.
     *
     * @return the transient failure
     */
    public static Failed transientFailure() {
      return TRANSIENT;
    }

    @Override
    public MinecraftChunk chunkOrUnknown() {
      return MinecraftChunk.Unknown.INSTANCE;
    }
  }

  /**
   * Returns a successful fetch of {@code chunk}.
   *
   * @param chunk the chunk
   * @return the fetch
   */
  static ChunkFetch success(MinecraftChunk chunk) {
    return new Success(chunk);
  }

  /**
   * Returns the chunk this fetch obtained, or {@link MinecraftChunk.Unknown} if it failed — what a
   * search reads either way.
   *
   * @return the chunk, or unknown
   */
  MinecraftChunk chunkOrUnknown();
}
