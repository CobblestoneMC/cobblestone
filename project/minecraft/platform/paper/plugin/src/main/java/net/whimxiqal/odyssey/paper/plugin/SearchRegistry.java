/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import org.bukkit.Location;

/**
 * Tracks each player's in-flight searches so they can be cancelled on {@code /odyssey cancel} or
 * logout. A search removes itself when its future completes.
 */
final class SearchRegistry {

  private final Map<UUID, Set<SearchHandle<Step<Location, MinecraftStepPayload>>>> byPlayer =
      new ConcurrentHashMap<>();

  void track(UUID player, SearchHandle<Step<Location, MinecraftStepPayload>> handle) {
    byPlayer.computeIfAbsent(player, key -> ConcurrentHashMap.newKeySet()).add(handle);
  }

  void untrack(UUID player, SearchHandle<Step<Location, MinecraftStepPayload>> handle) {
    Set<SearchHandle<Step<Location, MinecraftStepPayload>>> handles = byPlayer.get(player);
    if (handles != null) {
      handles.remove(handle);
      if (handles.isEmpty()) {
        byPlayer.remove(player);
      }
    }
  }

  /**
   * Cancels and forgets all of a player's searches.
   *
   * @param player the player id
   * @return how many searches were cancelled
   */
  int cancelAll(UUID player) {
    Set<SearchHandle<Step<Location, MinecraftStepPayload>>> handles = byPlayer.remove(player);
    if (handles == null) {
      return 0;
    }
    List<SearchHandle<Step<Location, MinecraftStepPayload>>> snapshot = new ArrayList<>(handles);
    snapshot.forEach(SearchHandle::cancel);
    return snapshot.size();
  }
}
