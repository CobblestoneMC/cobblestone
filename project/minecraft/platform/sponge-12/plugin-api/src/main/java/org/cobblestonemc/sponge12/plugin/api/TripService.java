/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.cobblestonemc.api.FailureReason;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.api.TripOutcome;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * Starts guided <b>trips</b> for a player — the "actually take me there" half that a search alone
 * doesn't do. Fetch it via {@link CobblestonePluginApi#tripService()}. Two entry points: {@link
 * #navigate} (search to a destination, then start a trip if a route is found) and {@link
 * #startTrip} (start a trip along a {@link Path} you already computed).
 *
 * <p>How the trip is drawn comes from {@link NavigatorSettings}; {@link
 * NavigatorSettings#defaults()} uses the server's default navigator.
 */
public interface TripService {

  /**
   * Searches to {@code destination}, then starts a trip if a route is found. Non-blocking.
   *
   * <p>{@code label} is the trip's stable identity: a fresh {@code navigate} with the same {@code
   * label} <em>replaces</em> that player's previous trip rather than stacking a new one. Pass
   * {@code null} to label the trip by its coordinates.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param label the stable trip identity, or {@code null} for a coordinate label
   * @return the outcome, once the search completes and the trip is (or is not) started
   */
  CompletableFuture<TripOutcome> navigate(
      ServerPlayer player, ServerLocation destination, NavigatorSettings settings, String label);

  /**
   * {@link #navigate(ServerPlayer, ServerLocation, NavigatorSettings, String)} with a coordinate
   * label.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @return the outcome, once the search completes and the trip is (or is not) started
   */
  default CompletableFuture<TripOutcome> navigate(
      ServerPlayer player, ServerLocation destination, NavigatorSettings settings) {
    return navigate(player, destination, settings, (String) null);
  }

  /**
   * {@link #navigate(ServerPlayer, ServerLocation, NavigatorSettings)} with an error callback.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param onError invoked (with the reason) if no route is found or the search fails
   */
  default void navigate(
      ServerPlayer player,
      ServerLocation destination,
      NavigatorSettings settings,
      Consumer<FailureReason> onError) {
    reportFailure(navigate(player, destination, settings, (String) null), onError);
  }

  /**
   * {@link #navigate(ServerPlayer, ServerLocation, NavigatorSettings, String)} with an error
   * callback.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param label the stable trip identity, or {@code null} for a coordinate label
   * @param onError invoked (with the reason) if no route is found or the search fails
   */
  default void navigate(
      ServerPlayer player,
      ServerLocation destination,
      NavigatorSettings settings,
      String label,
      Consumer<FailureReason> onError) {
    reportFailure(navigate(player, destination, settings, label), onError);
  }

  private static void reportFailure(
      CompletableFuture<TripOutcome> outcome, Consumer<FailureReason> onError) {
    outcome.thenAccept(
        o -> {
          if (o instanceof TripOutcome.Failed failed) {
            onError.accept(failed.reason());
          }
        });
  }

  /**
   * Starts a trip along an already-computed path (no re-search on stray).
   *
   * @param player the player to guide
   * @param path the path to follow
   * @param settings which navigator to display with, and its overrides
   * @return the outcome, once the trip is (or is not) started on the path's region thread
   */
  CompletableFuture<TripOutcome> startTrip(
      ServerPlayer player,
      Path<ServerLocation, MinecraftStepPayload> path,
      NavigatorSettings settings);
}
