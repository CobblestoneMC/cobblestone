/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.api.PaperNavigationService;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Starts guided <b>trips</b> for a player — the "actually take me there" half that a search alone
 * doesn't do. Fetch it via {@link Odyssey#tripService()}. Two entry points:
 *
 * <ul>
 *   <li>{@link #navigate} — the common one: search to a destination and, if a route is found, start
 *       a trip. The integration supplies only where to go and how to display it; Odyssey runs the
 *       search off-thread and creates the trip on the destination's region thread.
 *   <li>{@link #startTrip} — start a trip along a {@link Path} you already computed (via {@link
 *       PaperNavigationService}); no re-search.
 * </ul>
 *
 * <p>How the trip is drawn comes from {@link NavigatorSettings} (which navigator, and its per-trip
 * overrides); {@link NavigatorSettings#defaults()} uses the server's default navigator.
 */
public interface PaperTripService {

  /**
   * Searches to {@code destination}, then starts a trip if a route is found. Non-blocking.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @return the outcome, once the search completes and the trip is (or is not) started
   */
  CompletableFuture<TripOutcome> navigate(
      Player player, Location destination, NavigatorSettings settings);

  /**
   * {@link #navigate} with an error callback — the common integration shape.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param onError invoked (with the reason) if no route is found or the search fails
   */
  default void navigate(
      Player player,
      Location destination,
      NavigatorSettings settings,
      Consumer<FailureReason> onError) {
    navigate(player, destination, settings)
        .thenAccept(
            outcome -> {
              if (outcome instanceof TripOutcome.Failed failed) {
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
      Player player, Path<Location, MinecraftStepPayload> path, NavigatorSettings settings);

  /** The result of asking to start a trip. */
  sealed interface TripOutcome {

    /** A trip was started. */
    record Started(int tripId, double durationSeconds) implements TripOutcome {}

    /** No route was found (or the search failed). */
    record Failed(FailureReason reason) implements TripOutcome {}

    /** The player is already at their trip limit. */
    record TripLimitReached() implements TripOutcome {}
  }
}
