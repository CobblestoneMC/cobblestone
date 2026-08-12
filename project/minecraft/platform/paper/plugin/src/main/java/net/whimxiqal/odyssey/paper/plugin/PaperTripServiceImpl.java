/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.api.FailureReason;
import net.whimxiqal.odyssey.api.NavigationResult;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftSearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.PaperNavigationServiceImpl;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorFactory;
import net.whimxiqal.odyssey.paper.plugin.api.PaperNavigatorService;
import net.whimxiqal.odyssey.paper.plugin.api.PaperTripService;
import net.whimxiqal.odyssey.paper.plugin.api.TrailNavigatorSettings;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import net.whimxiqal.odyssey.plugin.trip.GuideSearch;
import net.whimxiqal.odyssey.plugin.trip.LiveSearch;
import net.whimxiqal.odyssey.plugin.trip.Trip;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * The plugin-side {@link PaperTripService}: it owns the search-then-start-trip flow so integrations
 * (and the {@code /navigate} command) share one path. The search runs off-thread; the navigator is
 * created and the trip started on the destination's owning region thread (Folia-safe).
 */
public final class PaperTripServiceImpl implements PaperTripService {

  // A tiny, greedy search for the off-trail guide path back to the current step.
  private static final SearchSettings GUIDE_SETTINGS =
      SearchSettings.builder()
          .maxCellsVisited(4000)
          .maxWallClockMillis(1500L)
          .heuristicWeight(2.0)
          .build();

  private final PaperNavigationServiceImpl platformApi;
  private final TripManager<Entity, PaperTripAgent, Location> trips;
  private final SearchRegistry searches;
  private final SearchGate gate;
  private final Supplier<SearchSettings> searchSettings;
  private final long liveIntervalMillis;

  PaperTripServiceImpl(
      PaperNavigationServiceImpl platformApi,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      long liveIntervalMillis) {
    this.platformApi = platformApi;
    this.trips = trips;
    this.searches = searches;
    this.gate = gate;
    this.searchSettings = searchSettings;
    this.liveIntervalMillis = liveIntervalMillis;
  }

  @Override
  public CompletableFuture<TripOutcome> navigate(
      Player player, Location destination, NavigatorSettings settings, String label) {
    UUID uuid = player.getUniqueId();
    gate.beginForced(uuid);
    SearchHandle<Location, MinecraftStepPayload> handle =
        platformApi.navigatePlayer(player, destination, defaults());
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
                case NavigationResult.Error<Location, MinecraftStepPayload> v -> {
                  outcome.complete(new TripOutcome.Error(v.throwable()));
                }
                case NavigationResult.Failure<Location, MinecraftStepPayload> v -> {
                  outcome.complete(new TripOutcome.Failed(v.reason()));
                  return;
                }
                case NavigationResult.Success<Location, MinecraftStepPayload> v -> {
                  Path<Location, MinecraftStepPayload> path =
                      ((NavigationResult.Success<Location, MinecraftStepPayload>) result).path();
                  // A trip to a fixed location:
                  // no periodic re-plan, but recalc-on-stray and a guideline back.
                  start(
                          player,
                          path,
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
              }
            });
    return outcome;
  }

  @Override
  public CompletableFuture<TripOutcome> startTrip(
      Player player, Path<Location, MinecraftStepPayload> path, NavigatorSettings settings) {
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
  CompletableFuture<TripOutcome> start(
      Player player,
      Path<Location, MinecraftStepPayload> path,
      String label,
      NavigatorSettings settings,
      LiveSearch<Location> liveSearch,
      GuideSearch<Location> guideSearch,
      boolean live) {
    CompletableFuture<TripOutcome> outcome = new CompletableFuture<>();
    Location origin = path.steps().isEmpty() ? null : path.steps().getFirst().position();
    if (origin == null || origin.getWorld() == null) {
      outcome.complete(
          new TripOutcome.Error(new IllegalArgumentException("origin and world may not be null")));
      return outcome;
    }
    PaperNavigatorFactory factory =
        navigatorFactory(settings.navigatorId().orElse(TrailNavigatorSettings.NAVIGATOR_ID));
    // Re-navigating to a place you already have a trip for replaces it rather than piling on.
    trips.cancelByDestination(player.getUniqueId(), label);
    platformApi
        .scheduler()
        .runAtPosition(
            platformApi.position(origin),
            () -> {
              if (!player.isOnline()) {
                outcome.complete(new TripOutcome.Failed(FailureReason.CANCELLED));
                return;
              }
              Navigator<Location> navigator = factory.create(player, path, settings);
              Optional<Trip<Entity, PaperTripAgent, Location>> trip =
                  trips.start(
                      new PaperTripAgent(player),
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
  private LiveSearch<Location> liveSearch(Player player, Location destination) {
    UUID uuid = player.getUniqueId();
    return () -> {
      if (!player.isOnline() || !gate.tryBegin(uuid)) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      SearchHandle<Location, MinecraftStepPayload> handle =
          platformApi.navigatePlayer(player, destination, defaults());
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
  private GuideSearch<Location> guideSearch(Player player) {
    return target -> {
      if (!player.isOnline()) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      MinecraftSearchSettings settings =
          new MinecraftSearchSettings(GUIDE_SETTINGS, Set.of(), Set.of(), Set.of());
      return platformApi
          .navigatePlayer(player, target, settings)
          .future()
          .handle((result, error) -> successPath(result, error));
    };
  }

  private static Optional<Path<Location, MinecraftStepPayload>> successPath(
      NavigationResult<Location, MinecraftStepPayload> result, Throwable error) {
    if (error == null
        && result
            instanceof
            NavigationResult.Success<Location, MinecraftStepPayload>(
                Path<Location, MinecraftStepPayload> path)
        && !path.steps().isEmpty()) {
      return Optional.of(path);
    }
    return Optional.empty();
  }

  private MinecraftSearchSettings defaults() {
    return new MinecraftSearchSettings(searchSettings.get(), Set.of(), Set.of(), Set.of());
  }

  private static PaperNavigatorFactory navigatorFactory(String id) {
    PaperNavigatorFactory fallback = null;
    for (RegisteredServiceProvider<PaperNavigatorService> registration :
        Bukkit.getServicesManager().getRegistrations(PaperNavigatorService.class)) {
      PaperNavigatorService service = registration.getProvider();
      Map<String, PaperNavigatorFactory> factories = service.compute();
      PaperNavigatorFactory factory = factories.get(id);
      if (factory != null) {
        return factory;
      }
      fallback = factories.get(TrailNavigatorSettings.NAVIGATOR_ID);
    }
    return fallback;
  }

  private static String describe(Location location) {
    return location.getWorld().getKey().asString()
        + " "
        + location.getBlockX()
        + ","
        + location.getBlockY()
        + ","
        + location.getBlockZ();
  }

  /** Labels an already-computed path by its final step's position. */
  private static String describe(Path<Location, MinecraftStepPayload> path) {
    return path.steps().isEmpty()
        ? "path"
        : describe(path.steps().get(path.steps().size() - 1).position());
  }
}
