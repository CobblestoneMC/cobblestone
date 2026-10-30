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
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.Workers;
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
      (chunkX, sectionY, chunkZ) -> FutureOr.of((x, y, z) -> TestBlocks.solid());

  @Test
  void changesAreForgottenTogetherOnceTheDelayHasPassed() throws InterruptedException {
    TestWorld world = TestWorld.builder("w").build();
    try (Workers workers = new Workers(new QuietLogger(), 1, 1)) {
      RegionStores stores =
          new RegionStores(Long.MAX_VALUE, workers.compute(), workers.time(), DELAY);
      RegionStore<MinecraftChunk, RegionClass> store = stores.of(world);
      store.section(0, 0, 0, ROCK);

      stores.invalidate(world, 5, 5, 5);
      stores.invalidate(world, 5, 5, 5);
      assertTrue(store.inMemory(0, 0, 0), "a change is not forgotten at once");

      Thread.sleep(DELAY.multipliedBy(3).toMillis());
      assertFalse(store.inMemory(0, 0, 0), "but once the delay has passed");

      // Built again: the second change above went with the first, so nothing forgets it now.
      store.section(0, 0, 0, ROCK);
      Thread.sleep(DELAY.multipliedBy(3).toMillis());
      assertTrue(store.inMemory(0, 0, 0), "one batch, one forgetting");

      stores.invalidate(world, 5, 5, 5);
      Thread.sleep(DELAY.multipliedBy(3).toMillis());
      assertFalse(store.inMemory(0, 0, 0), "a later change starts a batch of its own");
    }
  }

  private static final class QuietLogger extends CobblestoneLogger {
    @Override
    public void trace(String message, Object... args) {}

    @Override
    public void debug(String message, Object... args) {}

    @Override
    public void info(String message, Object... args) {}

    @Override
    public void warn(String message, Object... args) {}

    @Override
    public void error(String message, Throwable throwable, Object... args) {}
  }
}
