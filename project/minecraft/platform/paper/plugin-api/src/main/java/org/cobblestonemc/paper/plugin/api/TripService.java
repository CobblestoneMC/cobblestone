/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.cobblestonemc.api.FailureReason;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.paper.api.NavigationService;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.api.TripOutcome;

/**
 * Starts guided <b>trips</b> for a player — the "actually take me there" half that a search alone
 * doesn't do. Fetch it via {@link CobblestonePaperApi#tripService()}. Two entry points:
 *
 * <ul>
 *   <li>{@link #navigate} — the common one: search to a destination and, if a route is found, start
 *       a trip. The integration supplies only where to go and how to display it; Cobblestone runs
 *       the search off-thread and creates the trip on the destination's region thread.
 *   <li>{@link #startTrip} — start a trip along a {@link Path} you already computed (via {@link
 *       NavigationService}); no re-search.
 * </ul>
 *
 * <p>How the trip is drawn comes from {@link NavigatorSettings} (which navigator, and its per-trip
 * overrides); {@link NavigatorSettings#defaults()} uses the server's default navigator.
 */
public interface TripService {

  /**
   * Searches to {@code destination}, then starts a trip if a route is found. Non-blocking.
   *
   * <p>{@code label} names the trip to the player — in {@code /cobblestone trips} and, exactly as
   * given, in their trip sidebar — so keep it short, and tell your own trips apart with it. It is
   * also the trip's stable identity: crucially for a moving target you re-navigate to repeatedly (a
   * quest objective, an escort), a fresh {@code navigate} with the same {@code label}
   * <em>replaces</em> that player's previous trip rather than stacking a new one.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param label the trip's name and stable identity
   * @return the outcome, once the search completes and the trip is (or is not) started
   */
  CompletableFuture<TripOutcome> navigate(
      Player player, Location destination, NavigatorSettings settings, String label);

  /**
   * {@link #navigate(Player, Location, NavigatorSettings, String)} with an error callback — the
   * common integration shape.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param label the trip's name and stable identity
   * @param onError invoked (with the reason) if no route is found or the search fails
   */
  default void navigate(
      Player player,
      Location destination,
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
   * @param label the trip's name and stable identity, as for {@link #navigate(Player, Location,
   *     NavigatorSettings, String) navigate}
   * @return the outcome, once the trip is (or is not) started on the path's region thread
   */
  CompletableFuture<TripOutcome> startTrip(
      Player player,
      Path<Location, MinecraftStepPayload> path,
      NavigatorSettings settings,
      String label);
}
