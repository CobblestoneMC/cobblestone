/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import org.bukkit.Location;

/**
 * Tracks each player's in-flight searches so they can be cancelled on {@code /odyssey cancel} or
 * logout. A search removes itself when its future completes. Also counts search starts over a
 * trailing hour for the "searches per hour" metric.
 */
final class SearchRegistry {

  private final Map<UUID, Set<SearchHandle<Location, MinecraftStepPayload>>> byPlayer =
      new ConcurrentHashMap<>();
  private final SlidingWindowCounter searchRate = new SlidingWindowCounter(Duration.ofHours(1));

  void track(UUID player, SearchHandle<Location, MinecraftStepPayload> handle) {
    searchRate.record();
    byPlayer.computeIfAbsent(player, key -> ConcurrentHashMap.newKeySet()).add(handle);
  }

  void untrack(UUID player, SearchHandle<Location, MinecraftStepPayload> handle) {
    Set<SearchHandle<Location, MinecraftStepPayload>> handles = byPlayer.get(player);
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
  int active() {
    return byPlayer.values().stream().mapToInt(Set::size).sum();
  }

  /**
   * Returns how many searches were started in the trailing hour — i.e. the searches-per-hour rate
   * (an integer, since the window is exactly an hour). Live-trip re-searches count.
   *
   * @return the number of searches started in the last hour
   */
  int searchesLastHour() {
    return searchRate.count();
  }

  /**
   * Cancels and forgets all of a player's searches.
   *
   * @param player the player id
   * @return how many searches were cancelled
   */
  int cancelAll(UUID player) {
    Set<SearchHandle<Location, MinecraftStepPayload>> handles = byPlayer.remove(player);
    if (handles == null) {
      return 0;
    }
    List<SearchHandle<Location, MinecraftStepPayload>> snapshot = new ArrayList<>(handles);
    snapshot.forEach(SearchHandle::cancel);
    return snapshot.size();
  }
}
