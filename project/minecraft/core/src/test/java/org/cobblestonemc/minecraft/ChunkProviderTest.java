/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.junit.jupiter.api.Test;

class ChunkProviderTest {

  /** Two chunks' worth of read-ahead, so the prefetched column spans more than one neighbour. */
  private static final int PREFETCH_DISTANCE = 32;

  /** How many transient failures a chunk may have before the provider gives up on it. */
  private static final int MAX_ATTEMPTS = 3;

  private final AtomicLong clock = new AtomicLong(0);
  private final TestWorld world = TestWorld.builder("w").build();

  /** A destination far to the east (+x), so read-ahead runs along the +x axis. */
  private static final Cell EAST = new Cell(1000, 64, 0);

  private ChunkProvider provider(FakePlatform platform, ChunkProviderSettings settings) {
    return new ChunkProvider(platform, settings, clock::get);
  }

  private static ChunkProviderSettings settings() {
    return settings(1024, PREFETCH_DISTANCE);
  }

  private static ChunkProviderSettings settings(int capacity, int prefetchDistance) {
    return new ChunkProviderSettings(
        capacity, prefetchDistance, MAX_ATTEMPTS, ChunkLoadPolicy.ALLOW_LOAD);
  }

  @Test
  void secondBlockInCachedChunkIsImmediate() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings());

    FutureOr<MinecraftBlock> first = cp.block(new Cell(5, 64, 5), world, EAST);
    assertFalse(first.isImmediate(), "a miss is served as pending");
    first.future().join();

    assertTrue(
        cp.block(new Cell(6, 64, 6), world, EAST).isImmediate(),
        "same-chunk block is now a cache hit");
    assertEquals(1, platform.fetchCount(0, 0));
  }

  @Test
  void concurrentMissesForOneChunkFetchOnlyOnce() {
    FakePlatform platform = new FakePlatform();
    platform.setImmediate(false);
    ChunkProvider cp = provider(platform, settings());

    FutureOr<MinecraftBlock> a = cp.block(new Cell(5, 64, 5), world, EAST);
    FutureOr<MinecraftBlock> b = cp.block(new Cell(6, 64, 6), world, EAST);
    assertFalse(a.isImmediate());
    assertFalse(b.isImmediate());
    assertEquals(1, platform.fetchCount(0, 0), "the in-flight fetch is de-duplicated");

    platform.completeFetch(0, 0);
    a.future().join();
    b.future().join();
  }

  @Test
  void readAheadPrefetchesTheColumnTowardsTheDestination() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings());

    // From the middle of chunk [0, 0], heading due east for 32 blocks: the column covers the two
    // chunks ahead, and — because it is 5 blocks wide — nothing to the north or south of them.
    cp.block(new Cell(8, 64, 8), world, EAST).future().join();
    assertEquals(1, platform.fetchCount(0, 0), "the cell's own chunk, fetched urgently");
    assertEquals(1, platform.fetchCount(1, 0));
    assertEquals(1, platform.fetchCount(2, 0));
    assertEquals(0, platform.fetchCount(-1, 0), "nothing behind the search");
    assertEquals(0, platform.fetchCount(0, 1), "nothing lateral");
    assertEquals(0, platform.fetchCount(0, -1));
    assertEquals(0, platform.fetchCount(1, 1));
    assertEquals(0, platform.fetchCount(3, 0), "nothing past the read-ahead distance");
  }

  @Test
  void readAheadStopsAtTheDestination() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings());

    // The destination is only 8 blocks away, well short of the read-ahead distance.
    cp.block(new Cell(8, 64, 8), world, new Cell(16, 64, 8)).future().join();
    assertEquals(1, platform.fetchCount(1, 0), "the chunk the destination sits in");
    assertEquals(0, platform.fetchCount(2, 0), "nothing beyond the destination");
  }

  @Test
  void readAheadOnADiagonalCoversTheChunksTheColumnCrosses() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings());

    // Due south-east from the centre of chunk [0, 0]: 32 blocks along the diagonal is ~23 blocks
    // of x and z, so the column crosses [1, 0]/[0, 1] on its way into [1, 1].
    cp.block(new Cell(8, 64, 8), world, new Cell(1000, 64, 1000)).future().join();
    assertEquals(1, platform.fetchCount(1, 0));
    assertEquals(1, platform.fetchCount(0, 1));
    assertEquals(1, platform.fetchCount(1, 1));
    assertEquals(0, platform.fetchCount(-1, -1), "nothing behind the search");
    assertEquals(0, platform.fetchCount(2, 0), "nothing off to the side of the column");
    assertEquals(0, platform.fetchCount(3, 3), "nothing past the read-ahead distance");
  }

  @Test
  void aBorderCellStillPullsInTheChunkBehindIt() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings());

    // Sitting on the western border of chunk [0, 0] and heading east: modes read a block or two
    // back, so the chunk just behind is still worth having.
    cp.block(new Cell(0, 64, 8), world, EAST).future().join();
    assertEquals(1, platform.fetchCount(-1, 0));
  }

  @Test
  void withoutADestinationOnlyTheNeighbouringChunksAreRead() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings());

    cp.block(new Cell(0, 64, 0), world, null).future().join();
    assertEquals(1, platform.fetchCount(-1, -1), "the corner the cell touches");
    assertEquals(1, platform.fetchCount(-1, 0));
    assertEquals(1, platform.fetchCount(0, -1));
    assertEquals(0, platform.fetchCount(1, 0), "the far side of the cell's own chunk");
    assertEquals(0, platform.fetchCount(1, 1));
  }

  @Test
  void repeatReadsOfAPermanentlyUnknownChunkAreAnsweredFromTheCache() {
    FakePlatform platform = new FakePlatform();
    platform.setFailure(ChunkFetch.Failed.permanent());
    ChunkProvider cp = provider(platform, settings());

    cp.block(new Cell(5, 64, 5), world, EAST).future().join();
    assertEquals(1, platform.fetchCount(0, 0));

    assertTrue(
        cp.block(new Cell(6, 64, 6), world, EAST).isImmediate(),
        "a search pressed against absent terrain must not re-ask for every block");
    assertEquals(1, platform.fetchCount(0, 0));
  }

  /**
   * The cache belongs to one solve and is sized to its frontier, so the least recently used chunk
   * goes when a new one arrives. This is the only thing that evicts now: there is no staleness
   * window and no invalidation, because nothing a solve caches outlives the solve.
   */
  @Test
  void theLeastRecentlyUsedChunkIsDroppedOnceTheCacheIsFull() {
    FakePlatform platform = new FakePlatform();
    // Capacity two, no read-ahead, so exactly the chunks asked for are the chunks cached.
    ChunkProvider cp = provider(platform, settings(2, 0));

    cp.block(new Cell(0, 64, 0), world, null).future().join();
    cp.block(new Cell(16, 64, 0), world, null).future().join();
    // Touch the first again so the second becomes the least recently used.
    assertTrue(cp.block(new Cell(0, 64, 0), world, null).isImmediate());

    cp.block(new Cell(32, 64, 0), world, null).future().join();

    assertTrue(cp.block(new Cell(0, 64, 0), world, null).isImmediate(), "recently used, kept");
    assertFalse(cp.block(new Cell(16, 64, 0), world, null).isImmediate(), "evicted, read again");
    assertEquals(2, platform.fetchCount(1, 0));
  }

  @Test
  void readAheadCanBeSwitchedOff() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(1024, 0));

    cp.block(new Cell(5, 64, 5), world, EAST).future().join();

    assertEquals(1, platform.fetchCount(0, 0), "the chunk asked for");
    assertEquals(0, platform.fetchCount(1, 0), "and nothing ahead of it");
  }

  /**
   * A permanent failure is cached like any other answer, read-ahead included.
   *
   * <p>There is no such thing as a speculative unknown: a platform answers a request it was not
   * blocked on exactly as it answers one it was, resolving its own fallbacks before it replies (see
   * {@link PlatformApi#fetchChunk}). So a permanent failure from read-ahead is a fact about the
   * world, and re-asking would have a solve re-read it for every cell of a frontier pressed against
   * it.
   */
  @Test
  void aPermanentFailureFromReadAheadIsCachedLikeAnyOtherAnswer() {
    FakePlatform platform = new FakePlatform();
    platform.setFailure(ChunkFetch.Failed.permanent());
    ChunkProvider cp = provider(platform, settings());

    // Chunk [0, 0], heading east: reads ahead over [1, 0] and [2, 0], all unknown.
    cp.block(new Cell(8, 64, 8), world, EAST).future().join();
    assertEquals(1, platform.fetchCount(2, 0), "read ahead for once");

    // Crossing into [1, 0]: already answered by the read-ahead, so it is served without waiting.
    assertTrue(
        cp.block(new Cell(24, 64, 8), world, EAST).isImmediate(),
        "the read-ahead's answer is a cache hit, not a second fetch");

    assertEquals(1, platform.fetchCount(1, 0), "the chunk we walked into was not re-read");
    assertEquals(1, platform.fetchCount(2, 0), "nor was the one beyond it");
  }

  @Test
  void aTransientFailureIsAskedAgainOnTheNextRequest() {
    FakePlatform platform = new FakePlatform();
    platform.setFailure(ChunkFetch.Failed.transientFailure());
    ChunkProvider cp = provider(platform, settings(1024, 0));

    MinecraftBlock first = cp.block(new Cell(5, 64, 5), world, EAST).future().join();
    assertEquals(UnknownBlock.INSTANCE, first, "a failed fetch still answers, as a wall");

    platform.setFailure(null);
    FutureOr<MinecraftBlock> second = cp.block(new Cell(6, 64, 6), world, EAST);
    assertFalse(second.isImmediate(), "the failure was not cached, so this is a fresh fetch");
    assertNotEquals(UnknownBlock.INSTANCE, second.future().join());
    assertEquals(2, platform.fetchCount(0, 0));

    assertTrue(cp.block(new Cell(7, 64, 7), world, EAST).isImmediate(), "and now it is cached");
    assertEquals(2, platform.fetchCount(0, 0));
  }

  @Test
  void aChunkThatKeepsFailingTransientlyIsGivenUpOnAfterTheConfiguredAttempts() {
    FakePlatform platform = new FakePlatform();
    platform.setFailure(ChunkFetch.Failed.transientFailure());
    ChunkProvider cp = provider(platform, settings(1024, 0));

    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      FutureOr<MinecraftBlock> block = cp.block(new Cell(5, 64, 5), world, EAST);
      assertFalse(block.isImmediate(), "attempt " + (attempt + 1) + " goes back to the platform");
      block.future().join();
    }
    assertEquals(MAX_ATTEMPTS, platform.fetchCount(0, 0));

    FutureOr<MinecraftBlock> after = cp.block(new Cell(5, 64, 5), world, EAST);
    assertTrue(after.isImmediate(), "given up on: the wall is cached");
    assertEquals(UnknownBlock.INSTANCE, after.value());
    assertEquals(MAX_ATTEMPTS, platform.fetchCount(0, 0));
    assertEquals(MAX_ATTEMPTS, cp.stats().transientFailures());
    assertEquals(1, cp.stats().unknownChunks());
  }

  @Test
  void aFetchThatFailsExceptionallyCountsAsTransient() {
    FakePlatform platform = new FakePlatform();
    platform.setThrowing(true);
    ChunkProvider cp = provider(platform, settings(1024, 0));

    assertEquals(
        UnknownBlock.INSTANCE,
        cp.block(new Cell(5, 64, 5), world, EAST).future().join(),
        "a broken platform reads as a wall, not as a failed search");
    assertFalse(cp.block(new Cell(5, 64, 5), world, EAST).isImmediate(), "and is asked again");
    assertEquals(2, platform.fetchCount(0, 0));
  }

  @Test
  void aTransientFailureFromReadAheadIsReadAgainWhenTheSolveGetsThere() {
    FakePlatform platform = new FakePlatform();
    platform.setFailure(ChunkFetch.Failed.transientFailure());
    ChunkProvider cp = provider(platform, settings());

    // Chunk [0, 0], heading east: reads ahead over [1, 0], which fails.
    cp.block(new Cell(8, 64, 8), world, EAST).future().join();
    assertEquals(1, platform.fetchCount(1, 0));

    platform.setFailure(null);
    FutureOr<MinecraftBlock> arrived = cp.block(new Cell(24, 64, 8), world, EAST);
    assertFalse(arrived.isImmediate(), "the read-ahead's failure was not remembered");
    assertNotEquals(UnknownBlock.INSTANCE, arrived.future().join());
    assertEquals(2, platform.fetchCount(1, 0));
  }
}
