/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.TestBlocks;
import org.cobblestonemc.minecraft.TestWorld;
import org.cobblestonemc.regions.RegionSection;
import org.cobblestonemc.regions.RegionStore;
import org.cobblestonemc.regions.TerrainSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Block changes are forgotten in batches, a delay after the first; a world can be started over. */
class RegionStoresTest {

  private static final Duration DELAY = Duration.ofMillis(200);

  private static final TerrainSource<MinecraftChunk> ROCK =
      (chunkX, sectionY, chunkZ) -> FutureOr.of((x, y, z) -> TestBlocks.solid());

  /** Every error logged. */
  private final List<String> errors = new CopyOnWriteArrayList<>();

  private final CobblestoneLogger log =
      new CobblestoneLogger() {
        @Override
        public void trace(String message, Object... args) {}

        @Override
        public void debug(String message, Object... args) {}

        @Override
        public void info(String message, Object... args) {}

        @Override
        public void warn(String message, Object... args) {}

        @Override
        public void error(String message, Throwable throwable, Object... args) {
          errors.add(message);
        }
      };

  @Test
  void changesAreForgottenTogetherOnceTheDelayHasPassed() throws InterruptedException {
    TestWorld world = TestWorld.builder("w").build();
    try (RegionStores stores = new RegionStores(null, Long.MAX_VALUE, 1, log, DELAY)) {
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

  @Test
  void clearingAWorldDeletesItsFilesAndStartsItAfresh(@TempDir Path root) {
    TestWorld world = TestWorld.builder("minecraft:w").build();
    try (RegionStores stores = new RegionStores(root, Long.MAX_VALUE, 1, log, DELAY)) {
      RegionStore<MinecraftChunk, RegionClass> store = stores.of(world);
      built(store, 0);
      stores.flush("minecraft:w").join();
      assertTrue(Files.exists(root.resolve("minecraft_w")));

      stores.clear("minecraft:w").join();
      assertFalse(Files.exists(root.resolve("minecraft_w")));
      RegionStore<MinecraftChunk, RegionClass> afresh = stores.of(world);
      assertNotSame(store, afresh);
      assertFalse(afresh.inMemory(0, 0, 0));
    }
  }

  @Test
  void aWriteThatFailsIsLoggedOnceUntilItRecovers(@TempDir Path root) throws Exception {
    TestWorld world = TestWorld.builder("w").build();
    // A file where the world's directory has to go: nothing can be written under it.
    Path blocked = Files.createFile(root.resolve("w"));
    try (RegionStores stores = new RegionStores(root, Long.MAX_VALUE, 1, log, DELAY)) {
      RegionStore<MinecraftChunk, RegionClass> store = stores.of(world);
      built(store, 0);
      stores.flush().handle((done, failed) -> null).join();
      stores.flush().handle((done, failed) -> null).join();
      assertEquals(1, errors.size(), "once, not at every flush: " + errors);

      Files.delete(blocked);
      stores.flush().join();
      built(store, 1);
      stores.flush().handle((done, failed) -> null).join();
      assertEquals(1, errors.size());
    }
  }

  /** Builds a section of rock, waiting for it: with files, it is read for on the disk thread. */
  private static void built(RegionStore<MinecraftChunk, RegionClass> store, int sectionX) {
    CompletableFuture<RegionSection> done = new CompletableFuture<>();
    store.section(sectionX, 0, 0, ROCK).whenReady(done::complete, Runnable::run);
    done.join();
  }
}
