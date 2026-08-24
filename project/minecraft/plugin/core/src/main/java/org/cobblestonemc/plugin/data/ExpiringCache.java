/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A tiny read-through cache whose entries go stale after {@link #TTL_NANOS one second}, used by the
 * caching DAOs to keep tab-completion off the database. Sized for the "hundreds of players typing
 * at once" case rather than for long-lived state: an entry is a snapshot of one player's rows, and
 * a write to that player's data {@linkplain #invalidate invalidates} it immediately, so the TTL
 * only bounds staleness caused by something outside this process.
 *
 * <p>Safe to call from any thread. Two threads missing on the same key may both load it; that is
 * cheaper than locking, and the loser simply overwrites with an equally fresh value.
 *
 * @param <K> the key type
 * @param <V> the cached value type
 */
final class ExpiringCache<K, V> {

  /** How long a loaded value may be served before it is re-read. */
  static final long TTL_NANOS = 1_000_000_000L;

  /**
   * Entries live for a second, so the map only grows within one second's worth of distinct keys.
   * Past this many, a miss also sweeps what has expired — bounding an idle server's leftovers
   * without a scheduled task.
   */
  private static final int SWEEP_THRESHOLD = 1024;

  private record Entry<V>(V value, long loadedAt) {}

  private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  /** Creates a cache on the system's monotonic clock. */
  ExpiringCache() {
    this(System::nanoTime);
  }

  /**
   * Creates a cache on the given clock.
   *
   * @param clock a monotonic source of nanoseconds
   */
  ExpiringCache(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * Returns the cached value for a key, loading it if absent or stale.
   *
   * @param key the key
   * @param loader reads the value from the underlying store
   * @return the cached or freshly loaded value
   */
  V get(K key, Function<K, V> loader) {
    long now = clock.getAsLong();
    Entry<V> entry = entries.get(key);
    if (entry != null && now - entry.loadedAt() < TTL_NANOS) {
      return entry.value();
    }
    V value = loader.apply(key);
    entries.put(key, new Entry<>(value, now));
    if (entries.size() > SWEEP_THRESHOLD) {
      entries.values().removeIf(stale -> now - stale.loadedAt() >= TTL_NANOS);
    }
    return value;
  }

  /**
   * Drops a key, so the next read goes to the store. Call after every write to that key's data.
   *
   * @param key the key to drop
   */
  void invalidate(K key) {
    entries.remove(key);
  }
}
