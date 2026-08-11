/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link PlatformApi} test double: records fetches and either completes them at once or defers
 * them.
 */
final class FakePlatform implements PlatformApi<Object> {

  private final List<long[]> fetched = new ArrayList<>();
  private final Map<Long, CompletableFuture<Optional<MinecraftChunk>>> deferred = new HashMap<>();
  private boolean immediate = true;

  void setImmediate(boolean immediate) {
    this.immediate = immediate;
  }

  int fetchCount(int chunkX, int chunkZ) {
    return (int) fetched.stream().filter(a -> a[0] == chunkX && a[1] == chunkZ).count();
  }

  void completeFetch(int chunkX, int chunkZ) {
    deferred.get(key(chunkX, chunkZ)).complete(Optional.of(new FakeChunk(chunkX, chunkZ)));
  }

  @Override
  public MinecraftScheduler<Object> scheduler() {
    throw new UnsupportedOperationException("not needed for chunk-provider tests");
  }

  @Override
  public CompletableFuture<Optional<MinecraftChunk>> fetchChunk(
      int chunkX, int chunkZ, MinecraftWorld world, ChunkLoadPolicy policy) {
    fetched.add(new long[] {chunkX, chunkZ});
    if (immediate) {
      return CompletableFuture.completedFuture(Optional.of(new FakeChunk(chunkX, chunkZ)));
    }
    CompletableFuture<Optional<MinecraftChunk>> future = new CompletableFuture<>();
    deferred.put(key(chunkX, chunkZ), future);
    return future;
  }

  private static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
  }

  /** A trivial chunk snapshot whose blocks are all solid. */
  private record FakeChunk(int cx, int cz) implements MinecraftChunk {

    @Override
    public int chunkX() {
      return cx;
    }

    @Override
    public int chunkZ() {
      return cz;
    }

    @Override
    public int minY() {
      return -64;
    }

    @Override
    public int maxY() {
      return 320;
    }

    @Override
    public MinecraftBlock block(int localX, int y, int localZ) {
      return TestBlocks.solid();
    }
  }
}
