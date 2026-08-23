/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.trip;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.cobblestonemc.Position;
import org.cobblestonemc.api.FailureReason;
import org.cobblestonemc.api.NavigationResult;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.api.TripOutcome;
import org.cobblestonemc.plugin.search.SearchGate;
import org.cobblestonemc.plugin.search.SearchRegistry;

/**
 * The platform-neutral "search then guide" flow that both {@code /navigate} and integrations share:
 * run a search off-thread, and on success start a {@link Trip} on the destination's owning region
 * thread (Folia-safe). Everything that touches native player/location types is a small hook a
 * platform subclass fills in; the flow, the search-budget bookkeeping, and the stray-recalc / guide
 * closures all live here so Paper and Sponge share one implementation.
 *
 * @param <P> the native player type
 * @param <L> the native location type
 * @param <E> the native entity type (what region tasks are scheduled against)
 * @param <A> the trip-agent type wrapping a player
 */
public abstract class AbstractTripService<P, L, E, A extends TripAgent<E>> {

  // A tiny, greedy search for the off-trail guide path back to the current step.
  private static final SearchSettings GUIDE_SETTINGS =
      SearchSettings.builder()
          .maxCellsVisited(4000)
          .maxWallClockMillis(1500L)
          .heuristicWeight(2.0)
          .build();

  private final TripManager<E, A, L> trips;
  private final SearchRegistry<L> searches;
  private final SearchGate gate;
  private final Supplier<SearchSettings> searchSettings;
  private final long liveIntervalMillis;

  protected AbstractTripService(
      TripManager<E, A, L> trips,
      SearchRegistry<L> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      long liveIntervalMillis) {
    this.trips = trips;
    this.searches = searches;
    this.gate = gate;
    this.searchSettings = searchSettings;
    this.liveIntervalMillis = liveIntervalMillis;
  }

  // --- platform hooks -------------------------------------------------------

  /** The player's unique id. */
  protected abstract UUID uuid(P player);

  /** Whether the player is still connected. */
  protected abstract boolean isOnline(P player);

  /** Wraps the player as a trip agent. */
  protected abstract A agent(P player);

  /** Starts a search toward {@code destination}, yielding native-located steps. */
  protected abstract SearchHandle<L, MinecraftStepPayload> navigate(
      P player, L destination, MinecraftSearchSettings settings);

  /** The core position for a native location, or {@code null} if its world is unresolved. */
  protected abstract Position<MinecraftWorld> position(L location);

  /** The platform scheduler (region-aware). */
  protected abstract MinecraftScheduler<E> scheduler();

  /** Resolves the navigator for {@code settings} and builds it for this trip. */
  protected abstract Navigator<L> createNavigator(
      P player, Path<L, MinecraftStepPayload> path, NavigatorSettings settings);

  /** A short human label for a location (its world and block coordinates). */
  protected abstract String describe(L location);

  // --- shared flow ----------------------------------------------------------

  /**
   * Searches to {@code destination}, then starts a trip if a route is found. Non-blocking.
   *
   * @param player the player to guide
   * @param destination where to route to
   * @param settings which navigator to display with, and its overrides
   * @param label the stable trip identity, or {@code null} for a coordinate label
   * @return the outcome, once the search completes and the trip is (or is not) started
   */
  public CompletableFuture<TripOutcome> navigate(
      P player, L destination, NavigatorSettings settings, String label) {
    UUID uuid = uuid(player);
    gate.beginForced(uuid);
    SearchHandle<L, MinecraftStepPayload> handle = navigate(player, destination, defaults());
    searches.track(uuid, handle);
    CompletableFuture<TripOutcome> outcome = new CompletableFuture<>();
    handle
        .future()
        .whenComplete(
            (result, error) -> {
              searches.untrack(uuid, handle);
              gate.end(uuid);
              if (error != null) {
                outcome.complete(new TripOutcome.Error(error));
                return;
              }
              switch (result) {
                case NavigationResult.Error<L, MinecraftStepPayload> v ->
                    outcome.complete(new TripOutcome.Error(v.throwable()));
                case NavigationResult.Failure<L, MinecraftStepPayload> v ->
                    outcome.complete(new TripOutcome.Failed(v.reason()));
                case NavigationResult.Success<L, MinecraftStepPayload> v ->
                    // A trip to a fixed location: no periodic re-plan, but recalc-on-stray and a
                    // guideline back.
                    start(
                            player,
                            v.path(),
                            label != null ? label : describe(destination),
                            settings,
                            liveSearch(player, destination),
                            guideSearch(player),
                            false)
                        .whenComplete(
                            (tripOutcome, tripError) ->
                                outcome.complete(
                                    tripError != null
                                        ? new TripOutcome.Error(tripError)
                                        : tripOutcome));
              }
            });
    return outcome;
  }

  /**
   * Starts a trip along an already-computed path (no re-search on stray).
   *
   * @param player the player to guide
   * @param path the path to follow
   * @param settings which navigator to display with, and its overrides
   * @return the outcome, once the trip is (or is not) started on the path's region thread
   */
  public CompletableFuture<TripOutcome> startTrip(
      P player, Path<L, MinecraftStepPayload> path, NavigatorSettings settings) {
    return start(player, path, describe(path), settings, null, null, false);
  }

  /**
   * The one shared trip-start path: replaces any same-destination trip, creates the navigator on
   * the path's region thread (Folia-safe), and starts the trip. Both the public API entry points
   * and the {@code /navigate} command funnel through here.
   *
   * @param player the player to guide
   * @param path the route to follow (its first step's world/region owns the trip)
   * @param label the destination label (for the trip listing and same-destination replacement)
   * @param settings which navigator to display with, and its per-trip overrides
   * @param liveSearch the re-search behavior, or {@code null} to disable re-searching
   * @param guideSearch the short-range off-trail guide search, or {@code null}
   * @param live whether to also re-search periodically
   * @return the outcome, completed once the trip is (or is not) started on the region thread
   */
  public CompletableFuture<TripOutcome> start(
      P player,
      Path<L, MinecraftStepPayload> path,
      String label,
      NavigatorSettings settings,
      LiveSearch<L> liveSearch,
      GuideSearch<L> guideSearch,
      boolean live) {
    CompletableFuture<TripOutcome> outcome = new CompletableFuture<>();
    L origin = path.steps().isEmpty() ? null : path.steps().getFirst().position();
    Position<MinecraftWorld> originPosition = origin == null ? null : position(origin);
    if (originPosition == null) {
      outcome.complete(
          new TripOutcome.Error(new IllegalArgumentException("origin and world may not be null")));
      return outcome;
    }
    // Re-navigating to a place you already have a trip for replaces it rather than piling on.
    trips.cancelByDestination(uuid(player), label);
    scheduler()
        .runAtPosition(
            originPosition,
            () -> {
              if (!isOnline(player)) {
                outcome.complete(new TripOutcome.Failed(FailureReason.CANCELLED));
                return;
              }
              Navigator<L> navigator = createNavigator(player, path, settings);
              Optional<Trip<E, A, L>> trip =
                  trips.start(
                      agent(player),
                      navigator,
                      label,
                      liveSearch,
                      guideSearch,
                      live,
                      liveIntervalMillis);
              outcome.complete(
                  trip.map(
                          started ->
                              (TripOutcome) new TripOutcome.Started(started.id(), path.duration()))
                      .orElseGet(TripOutcome.TripLimitReached::new));
            });
    return outcome;
  }

  /** Re-search to the fixed destination (stray recalculation), respecting the search budget. */
  private LiveSearch<L> liveSearch(P player, L destination) {
    UUID uuid = uuid(player);
    return () -> {
      if (!isOnline(player) || !gate.tryBegin(uuid)) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      SearchHandle<L, MinecraftStepPayload> handle = navigate(player, destination, defaults());
      searches.track(uuid, handle);
      return handle
          .future()
          .handle(
              (result, error) -> {
                searches.untrack(uuid, handle);
                gate.end(uuid);
                return successPath(result, error);
              });
    };
  }

  /** The short-range guide search (player -> current step) for off-trail drift. */
  private GuideSearch<L> guideSearch(P player) {
    return target -> {
      if (!isOnline(player)) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      MinecraftSearchSettings settings =
          new MinecraftSearchSettings(GUIDE_SETTINGS, Set.of(), Set.of(), Set.of());
      return navigate(player, target, settings).future().handle(AbstractTripService::successPath);
    };
  }

  private static <L> Optional<Path<L, MinecraftStepPayload>> successPath(
      NavigationResult<L, MinecraftStepPayload> result, Throwable error) {
    if (error == null
        && result
            instanceof
            NavigationResult.Success<L, MinecraftStepPayload>(Path<L, MinecraftStepPayload> path)
        && !path.steps().isEmpty()) {
      return Optional.of(path);
    }
    return Optional.empty();
  }

  private MinecraftSearchSettings defaults() {
    return new MinecraftSearchSettings(searchSettings.get(), Set.of(), Set.of(), Set.of());
  }

  /** Labels an already-computed path by its final step's position. */
  protected final String describe(Path<L, MinecraftStepPayload> path) {
    return path.steps().isEmpty()
        ? "path"
        : describe(path.steps().get(path.steps().size() - 1).position());
  }
}
