/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.plugin.util.SlidingWindowCounter;

/**
 * Tracks each player's in-flight searches so they can be cancelled on {@code /odyssey cancel} or
 * logout. A search removes itself when its future completes. Also counts search starts over a
 * trailing hour for the "searches per hour" metric.
 *
 * <p>Platform-neutral: parameterized over the native location type {@code L} so both platforms
 * share one implementation.
 *
 * @param <L> the native location type searches are located in
 */
public final class SearchRegistry<L> {

  private final Map<UUID, Set<SearchHandle<L, MinecraftStepPayload>>> byPlayer =
      new ConcurrentHashMap<>();
  private final SlidingWindowCounter searchRate = new SlidingWindowCounter(Duration.ofHours(1));

  public void track(UUID player, SearchHandle<L, MinecraftStepPayload> handle) {
    searchRate.record();
    byPlayer.computeIfAbsent(player, key -> ConcurrentHashMap.newKeySet()).add(handle);
  }

  public void untrack(UUID player, SearchHandle<L, MinecraftStepPayload> handle) {
    Set<SearchHandle<L, MinecraftStepPayload>> handles = byPlayer.get(player);
    if (handles != null) {
      handles.remove(handle);
      if (handles.isEmpty()) {
        byPlayer.remove(player);
      }
    }
  }

  /**
   * Returns the total number of in-flight searches across all players (for metrics).
   *
   * @return the active search count
   */
  public int active() {
    return byPlayer.values().stream().mapToInt(Set::size).sum();
  }

  /**
   * Returns how many searches were started in the trailing hour — i.e. the searches-per-hour rate
   * (an integer, since the window is exactly an hour). Live-trip re-searches count.
   *
   * @return the number of searches started in the last hour
   */
  public int searchesLastHour() {
    return searchRate.count();
  }

  /**
   * Cancels and forgets all of a player's searches.
   *
   * @param player the player id
   * @return how many searches were cancelled
   */
  public int cancelAll(UUID player) {
    Set<SearchHandle<L, MinecraftStepPayload>> handles = byPlayer.remove(player);
    if (handles == null) {
      return 0;
    }
    List<SearchHandle<L, MinecraftStepPayload>> snapshot = new ArrayList<>(handles);
    snapshot.forEach(SearchHandle::cancel);
    return snapshot.size();
  }
}
