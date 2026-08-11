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
   * <p>{@code label} is the trip's stable identity: it names the trip in {@code /navigate}'s
   * listing and — crucially for a moving target you re-navigate to repeatedly (a quest objective,
   * an escort) — a fresh {@code navigate} with the same {@code label} <em>replaces</em> that
   * player's previous trip rather than stacking a new one. Pass {@code null} to label the trip by
   * its coordinates (fine for a one-shot fixed destination, but two such trips never replace each
   * other).
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param label the stable trip identity, or {@code null} for a coordinate label
   * @return the outcome, once the search completes and the trip is (or is not) started
   */
  CompletableFuture<TripOutcome> navigate(
      Player player, Location destination, NavigatorSettings settings, String label);

  /**
   * {@link #navigate(Player, Location, NavigatorSettings, String)} with a coordinate label.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @return the outcome, once the search completes and the trip is (or is not) started
   */
  default CompletableFuture<TripOutcome> navigate(
      Player player, Location destination, NavigatorSettings settings) {
    return navigate(player, destination, settings, (String) null);
  }

  /**
   * {@link #navigate(Player, Location, NavigatorSettings)} with an error callback — the common
   * integration shape.
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
    reportFailure(navigate(player, destination, settings, (String) null), onError);
  }

  /**
   * {@link #navigate(Player, Location, NavigatorSettings, String)} with an error callback.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param label the stable trip identity, or {@code null} for a coordinate label
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

    record Error(Throwable throwable) implements TripOutcome {}
  }
}
