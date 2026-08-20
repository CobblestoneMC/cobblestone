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

  /** Two chunks' worth of read-ahead, so the prefetched column spans more than one neighbour. */
  private static final int PREFETCH_DISTANCE = 32;

  private final AtomicLong clock = new AtomicLong(0);
  private final TestWorld world = TestWorld.builder("w").build();

  /** A destination far to the east (+x), so read-ahead runs along the +x axis. */
  private static final Cell EAST = new Cell(1000, 64, 0);

  private ChunkProvider provider(FakePlatform platform, ChunkProviderSettings settings) {
    return new ChunkProvider(platform, settings, clock::get);
  }

  private static ChunkProviderSettings settings(long staleness) {
    return new ChunkProviderSettings(
        1024, staleness, PREFETCH_DISTANCE, ChunkLoadPolicy.ALLOW_LOAD);
  }

  @Test
  void secondBlockInCachedChunkIsImmediate() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(10_000));

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
    ChunkProvider cp = provider(platform, settings(10_000));

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
  void staleChunksAreRefetched() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(5_000));

    cp.block(new Cell(5, 64, 5), world, EAST).future().join();
    assertEquals(1, platform.fetchCount(0, 0));

    clock.set(5_001);
    cp.block(new Cell(5, 64, 5), world, EAST);
    assertEquals(2, platform.fetchCount(0, 0), "an expired snapshot is fetched again");
  }

  @Test
  void readAheadPrefetchesTheColumnTowardsTheDestination() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(10_000));

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
    ChunkProvider cp = provider(platform, settings(10_000));

    // The destination is only 8 blocks away, well short of the read-ahead distance.
    cp.block(new Cell(8, 64, 8), world, new Cell(16, 64, 8)).future().join();
    assertEquals(1, platform.fetchCount(1, 0), "the chunk the destination sits in");
    assertEquals(0, platform.fetchCount(2, 0), "nothing beyond the destination");
  }

  @Test
  void readAheadOnADiagonalCoversTheChunksTheColumnCrosses() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(10_000));

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
    ChunkProvider cp = provider(platform, settings(10_000));

    // Sitting on the western border of chunk [0, 0] and heading east: modes read a block or two
    // back, so the chunk just behind is still worth having.
    cp.block(new Cell(0, 64, 8), world, EAST).future().join();
    assertEquals(1, platform.fetchCount(-1, 0));
  }

  @Test
  void withoutADestinationOnlyTheNeighbouringChunksAreRead() {
    FakePlatform platform = new FakePlatform();
    ChunkProvider cp = provider(platform, settings(10_000));

    cp.block(new Cell(0, 64, 0), world, null).future().join();
    assertEquals(1, platform.fetchCount(-1, -1), "the corner the cell touches");
    assertEquals(1, platform.fetchCount(-1, 0));
    assertEquals(1, platform.fetchCount(0, -1));
    assertEquals(0, platform.fetchCount(1, 0), "the far side of the cell's own chunk");
    assertEquals(0, platform.fetchCount(1, 1));
  }

  @Test
  void doesNotCacheUnknownFromReadAhead() {
    FakePlatform platform = new FakePlatform();
    platform.setRefuseReadAhead(true);
    ChunkProvider provider = provider(platform, settings(10_000L));

    // Reading one cell drags the chunks ahead of it in as read-ahead, and those come back unknown.
    provider.block(new Cell(8, 64, 8), world, EAST).future().join();
    int neighbourX = 1;

    // A later urgent read of a refused neighbour must go back to the platform rather than be
    // answered from the refusal, which was only ever a statement about the read-ahead budget.
    int before = platform.fetchCount(neighbourX, 0);
    FutureOr<MinecraftBlock> block =
        provider.block(new Cell((neighbourX << 4) + 8, 64, 8), world, EAST);
    assertTrue(
        platform.fetchCount(neighbourX, 0) > before, "refused read-ahead must not be cached");
    assertNotNull(block);
  }
}
