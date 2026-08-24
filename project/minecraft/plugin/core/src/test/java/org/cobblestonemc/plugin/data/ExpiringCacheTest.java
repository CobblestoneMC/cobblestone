/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The read-through, one-second, invalidate-on-write behavior the caching DAOs rely on. */
class ExpiringCacheTest {

  private final AtomicLong clock = new AtomicLong();
  private final AtomicInteger loads = new AtomicInteger();
  private final ExpiringCache<String, String> cache = new ExpiringCache<>(clock::get);
  private final Function<String, String> loader =
      key -> {
        loads.incrementAndGet();
        return key + "-" + loads.get();
      };

  @Test
  void loadsOnceThenServesFromTheCache() {
    assertEquals("a-1", cache.get("a", loader));
    assertEquals("a-1", cache.get("a", loader));
    assertEquals(1, loads.get(), "a fresh entry must not hit the store again");
  }

  @Test
  void keysAreIndependent() {
    cache.get("a", loader);
    cache.get("b", loader);
    assertEquals(2, loads.get());
  }

  @Test
  void reloadsOnceTheEntryIsStale() {
    assertEquals("a-1", cache.get("a", loader));
    clock.addAndGet(ExpiringCache.TTL_NANOS - 1);
    assertEquals("a-1", cache.get("a", loader), "still fresh a nanosecond short of the TTL");

    clock.addAndGet(1);
    assertEquals("a-2", cache.get("a", loader));
    assertEquals(2, loads.get());
  }

  @Test
  void invalidateForcesTheNextReadToTheStore() {
    assertEquals("a-1", cache.get("a", loader));
    cache.invalidate("a");
    assertEquals("a-2", cache.get("a", loader));
  }
}
