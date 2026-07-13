/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.Step;

/**
 * The platform-flavored navigation façade: it re-exposes the generic core operations entirely in a
 * platform's native types, so a plugin developer never touches Odyssey's internal abstractions.
 *
 * <p>Both the inputs (player, locations) and the results are native: a returned
 * {@link SearchHandle} yields {@link Step}s whose position is the native location type {@code L}
 * (e.g. {@code org.bukkit.Location}), not an internal {@code Position}.
 *
 * @param <P> the native player type (e.g. {@code org.bukkit.entity.Player})
 * @param <L> the native location type (e.g. {@code org.bukkit.Location})
 */
public interface PlatformOdysseyApi<P, L> {

  /**
   * Begins a search from the player's current location toward {@code destination}.
   *
   * @param player the navigating player
   * @param destination the goal location
   * @param settings the search limits and knobs
   * @return a handle to the in-flight search, yielding native-located steps
   */
  SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayer(
      P player, L destination, SearchSettings settings);

  /**
   * Begins a search toward {@code destination} using default {@link SearchSettings}.
   *
   * @param player the navigating player
   * @param destination the goal location
   * @return a handle to the in-flight search
   */
  default SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayer(
      P player, L destination) {
    return navigatePlayer(player, destination, SearchSettings.defaults());
  }

  /**
   * Begins a search toward the axis-aligned box region spanned by two corner locations (which must
   * share a world); the search succeeds on reaching any cell of the box.
   *
   * @param player the navigating player
   * @param location1 one corner of the target region
   * @param location2 the opposite corner of the target region
   * @param settings the search limits and knobs
   * @return a handle to the in-flight search, yielding native-located steps
   */
  SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayerToRegion(
      P player, L location1, L location2, SearchSettings settings);

  /**
   * Begins a region search using default {@link SearchSettings}.
   *
   * @param player the navigating player
   * @param location1 one corner of the target region
   * @param location2 the opposite corner of the target region
   * @return a handle to the in-flight search
   */
  default SearchHandle<Step<L, MinecraftStepPayload>> navigatePlayerToRegion(
      P player, L location1, L location2) {
    return navigatePlayerToRegion(player, location1, location2, SearchSettings.defaults());
  }

  /**
   * Registers a transition provider so its wormholes are considered by future searches. The
   * implementation delegates to the plugin-owned {@link TransitionRegistry}.
   *
   * @param provider the provider to register
   */
  void registerTransitionProvider(PlatformSingleCellTransitionProvider<P, L> provider);
}
