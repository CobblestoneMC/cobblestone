/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.search;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-player concurrency budget for searches (design/06 {@code
 * max_concurrent_searches_per_player}, default 1). A manual {@code /navigate} always runs ({@link
 * #beginForced}) and counts toward the budget for its duration; live re-searches {@link #tryBegin}
 * and skip a cycle when the budget is full, so they yield to manual searches and serialize behind
 * one another.
 */
public final class SearchGate {

  private final int maxPerPlayer;
  private final Map<UUID, AtomicInteger> active = new ConcurrentHashMap<>();

  public SearchGate(int maxPerPlayer) {
    this.maxPerPlayer = Math.max(1, maxPerPlayer);
  }

  /**
   * Tries to reserve a search slot for a player.
   *
   * @param player the player id
   * @return {@code true} if a slot was reserved (release it with {@link #end}); {@code false} if
   *     the player is already at the budget
   */
  public boolean tryBegin(UUID player) {
    AtomicInteger count = active.computeIfAbsent(player, key -> new AtomicInteger());
    while (true) {
      int current = count.get();
      if (current >= maxPerPlayer) {
        return false;
      }
      if (count.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  /**
   * Reserves a slot unconditionally (a manual search always runs). Release it with {@link #end}.
   *
   * @param player the player id
   */
  public void beginForced(UUID player) {
    active.computeIfAbsent(player, key -> new AtomicInteger()).incrementAndGet();
  }

  /**
   * Releases a previously reserved slot.
   *
   * @param player the player id
   */
  public void end(UUID player) {
    AtomicInteger count = active.get(player);
    if (count != null) {
      count.decrementAndGet();
    }
  }
}
