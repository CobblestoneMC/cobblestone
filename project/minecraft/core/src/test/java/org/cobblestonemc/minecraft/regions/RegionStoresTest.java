/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.regions.RegionStore;
import org.cobblestonemc.regions.TerrainSource;
import org.junit.jupiter.api.Test;

/** Block changes are forgotten in batches, a delay after the first. */
class RegionStoresTest {

  private static final Duration DELAY = Duration.ofMillis(200);

  private static final TerrainSource<MinecraftChunk> ROCK =
      (chunkX, chunkZ) -> FutureOr.of((x, y, z) -> TestBlocks.solid());

  @Test
  void changesAreForgottenTogetherOnceTheDelayHasPassed() throws InterruptedException {
    TestWorld world = TestWorld.builder("w").build();
    try (RegionStores stores = new RegionStores(null, Long.MAX_VALUE, 1, DELAY)) {
      RegionStore<MinecraftChunk> store = stores.of(world);
      store.column(0, 0, ROCK);

      stores.invalidate(world, 0, 0);
      stores.invalidate(world, 0, 0);
      assertTrue(store.inMemory(0, 0), "a change is not forgotten at once");

      Thread.sleep(DELAY.multipliedBy(3).toMillis());
      assertFalse(store.inMemory(0, 0), "but once the delay has passed");

      // Built again: the second change above went with the first, so nothing forgets it now.
      store.column(0, 0, ROCK);
      Thread.sleep(DELAY.multipliedBy(3).toMillis());
      assertTrue(store.inMemory(0, 0), "one batch, one forgetting");

      stores.invalidate(world, 0, 0);
      Thread.sleep(DELAY.multipliedBy(3).toMillis());
      assertFalse(store.inMemory(0, 0), "a later change starts a batch of its own");
    }
  }
}
