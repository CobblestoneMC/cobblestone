/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import net.whimxiqal.odyssey.Cell;
import net.whimxiqal.odyssey.FutureOr;
import org.junit.jupiter.api.Test;

class ChunkProviderTest {

  private final AtomicLong clock = new AtomicLong(0);
  private final TestWorld world = TestWorld.builder("w").build();

  private ChunkProvider provider(FakePlatform platform, ChunkProviderSettings settings) {
    return new ChunkProvider(platform, settings, clock::get);
  }

  private static ChunkProviderSettings settings(long staleness) {
    return new ChunkProviderSettings(1024, staleness, ChunkLoadPolicy.ALLOW_LOAD);
  }

  @Test
  void secondBlockInCachedChunkIsImmediate() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(10_000));

    FutureOr<MinecraftBlock> first = cp.block(new Cell(5, 64, 5), world);
    assertFalse(first.isImmediate(), "a miss is served as pending");
    first.future().join();

    assertTrue(
        cp.block(new Cell(6, 64, 6), world).isImmediate(), "same-chunk block is now a cache hit");
    assertEquals(1, platform.fetchCount(0, 0));
  }

  @Test
  void concurrentMissesForOneChunkFetchOnlyOnce() {
    FakePlatform platform = new FakePlatform();
    platform.setImmediate(false);
    ChunkProvider cp = provider(platform, settings(10_000));

    FutureOr<MinecraftBlock> a = cp.block(new Cell(5, 64, 5), world);
    FutureOr<MinecraftBlock> b = cp.block(new Cell(6, 64, 6), world);
    assertFalse(a.isImmediate());
    assertFalse(b.isImmediate());
    assertEquals(1, platform.fetchCount(0, 0), "the in-flight fetch is de-duplicated");

    platform.completeFetch(0, 0);
    a.future().join();
    b.future().join();
  }

  @Test
  void staleChunksAreRefetched() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(5_000));

    cp.block(new Cell(5, 64, 5), world).future().join();
    assertEquals(1, platform.fetchCount(0, 0));

    clock.set(5_001);
    cp.block(new Cell(5, 64, 5), world);
    assertEquals(2, platform.fetchCount(0, 0), "an expired snapshot is fetched again");
  }

  @Test
  void readAheadPrefetchesTheAdjacentChunks() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(10_000));

    cp.block(new Cell(0, 64, 0), world).future().join();
    assertEquals(1, platform.fetchCount(-1, -1));
    assertEquals(1, platform.fetchCount(-1, 0));
    assertEquals(1, platform.fetchCount(-1, 1));
    assertEquals(1, platform.fetchCount(0, -1));
    assertEquals(1, platform.fetchCount(0, 0));
    assertEquals(1, platform.fetchCount(0, 1));
    assertEquals(1, platform.fetchCount(1, -1));
    assertEquals(1, platform.fetchCount(1, 0));
    assertEquals(1, platform.fetchCount(1, 1));
  }

  @Test
  void doesNotCacheUnknownFromReadAhead() {
    FakePlatform platform = new FakePlatform();
    platform.setRefuseReadAhead(true);
    ChunkProvider provider = provider(platform, settings(10_000L));

    // Reading one cell drags its neighbours in as read-ahead, and those come back unknown.
    provider.block(new Cell(0, 64, 0), world).future().join();
    int neighbourX = ChunkProvider.CHUNK_PREFETCH_MARGIN >> 4;

    // A later urgent read of a refused neighbour must go back to the platform rather than be
    // answered from the refusal, which was only ever a statement about the read-ahead budget.
    int before = platform.fetchCount(neighbourX, 0);
    FutureOr<MinecraftBlock> block = provider.block(new Cell(neighbourX << 4, 64, 0), world);
    assertTrue(
        platform.fetchCount(neighbourX, 0) > before, "refused read-ahead must not be cached");
    assertNotNull(block);
  }
}
