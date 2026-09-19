/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link PlatformApi} test double: records fetches and either completes them at once or defers
 * them.
 */
final class FakePlatform implements PlatformApi<Object> {

  private final List<long[]> fetched = new ArrayList<>();
  private final Map<Long, CompletableFuture<ChunkFetch>> deferred = new HashMap<>();
  private boolean immediate = true;
  private ChunkFetch.Failed failure;
  private boolean throwing;

  /**
   * Makes every fetch — urgent ones included — come back with this failure, or succeed again when
   * {@code null}.
   */
  void setFailure(ChunkFetch.Failed failure) {
    this.failure = failure;
  }

  /** Makes every fetch complete exceptionally, the way a platform with a bug in it would. */
  void setThrowing(boolean throwing) {
    this.throwing = throwing;
  }

  void setImmediate(boolean immediate) {
    this.immediate = immediate;
  }

  int fetchCount(int chunkX, int chunkZ) {
    return (int) fetched.stream().filter(a -> a[0] == chunkX && a[1] == chunkZ).count();
  }

  void completeFetch(int chunkX, int chunkZ) {
    deferred.get(key(chunkX, chunkZ)).complete(ChunkFetch.success(new FakeChunk(chunkX, chunkZ)));
  }

  @Override
  public MinecraftScheduler<Object> scheduler() {
    throw new UnsupportedOperationException("not needed for chunk-provider tests");
  }

  @Override
  public CompletableFuture<ChunkFetch> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy, boolean urgent) {
    fetched.add(new long[] {chunkX, chunkZ});
    if (throwing) {
      return CompletableFuture.failedFuture(new IllegalStateException("the platform broke"));
    }
    if (failure != null) {
      return CompletableFuture.completedFuture(failure);
    }
    if (immediate) {
      return CompletableFuture.completedFuture(ChunkFetch.success(new FakeChunk(chunkX, chunkZ)));
    }
    CompletableFuture<ChunkFetch> future = new CompletableFuture<>();
    deferred.put(key(chunkX, chunkZ), future);
    return future;
  }

  private static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
  }

  /** A trivial chunk snapshot whose blocks are all solid. */
  private record FakeChunk(int cx, int cz) implements MinecraftChunk {

    @Override
    public MinecraftBlock block(int localX, int y, int localZ) {
      return TestBlocks.solid();
    }
  }
}
