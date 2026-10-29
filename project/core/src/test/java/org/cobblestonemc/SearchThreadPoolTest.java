/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SearchThreadPoolTest {

  @Test
  void zeroLeavesTheCountToCobblestone() {
    assertEquals(SearchThreadPool.defaultSize(), SearchThreadPool.size(0));
    assertEquals(SearchThreadPool.defaultSize(), SearchThreadPool.size(-1));
    assertEquals(3, SearchThreadPool.size(3));
    assertTrue(SearchThreadPool.defaultSize() >= 2);
  }

  @Test
  void workRunsOnNamedDaemonThreads() throws Exception {
    SearchThreadPool pool = new SearchThreadPool("test-search", 1);
    try {
      CompletableFuture<Thread> now = new CompletableFuture<>();
      pool.runAsync(() -> now.complete(Thread.currentThread()));
      Thread thread = now.get(5, TimeUnit.SECONDS);
      assertEquals("test-search-1", thread.getName());
      assertTrue(thread.isDaemon());

      CompletableFuture<Void> later = new CompletableFuture<>();
      pool.runAsyncLater(() -> later.complete(null), 10);
      later.get(5, TimeUnit.SECONDS);
    } finally {
      pool.shutdown();
    }
  }
}
