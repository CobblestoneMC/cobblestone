/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.plugin.api.Navigator;

/**
 * Tracks each player's active {@link Trip}s and enforces {@code trips.max_active_per_player} (the
 * "path home + path to the caves at once" budget). Ticking cadence for every trip is a shared period.
 * Platform-neutral; the platform plugin builds one and calls {@link #stopAll} on logout and
 * {@link #stopEverything} on disable.
 *
 * @param <L> the native location type the navigators render in
 */
public final class TripManager<L> {

  /** Trips render every server tick; a coarser period is not worth a config knob. */
  private static final long TICK_PERIOD = 1L;

  private final MinecraftScheduler scheduler;
  private final int maxActivePerPlayer;
  private final Map<UUID, List<Trip<L>>> byPlayer = new ConcurrentHashMap<>();

  /**
   * Creates a trip manager.
   *
   * @param scheduler the scheduler trips tick on
   * @param maxActivePerPlayer the most simultaneous trips one player may run
   */
  public TripManager(MinecraftScheduler scheduler, int maxActivePerPlayer) {
    this.scheduler = scheduler;
    this.maxActivePerPlayer = maxActivePerPlayer;
  }

  /**
   * Starts a trip, unless the player is already at their trip limit.
   *
   * @param player the guided player's id
   * @param anchor the location whose owning thread the trip ticks on
   * @param navigatorId the navigator (display strategy) id
   * @param navigator the navigator to drive
   * @param destination the destination label (for the listing and same-destination replacement)
   * @param liveSearch the re-search behavior (used for both live loops and stray recalculation); may
   *     be {@code null} to disable re-searching entirely
   * @param guideSearch the short-range guide search for off-trail drift; may be {@code null}
   * @param live whether to also re-search periodically (a "live" trip)
   * @param liveIntervalMillis the delay between periodic re-searches, in milliseconds
   * @return the started trip, or empty if the player is at their limit
   */
  public synchronized Optional<Trip<L>> start(
      UUID player, Position<? extends MinecraftWorld> anchor, String navigatorId,
      Navigator<L> navigator, String destination, LiveSearch<L> liveSearch,
      GuideSearch<L> guideSearch, boolean live, long liveIntervalMillis) {
    List<Trip<L>> active = byPlayer.computeIfAbsent(player, key -> new ArrayList<>());
    if (active.size() >= maxActivePerPlayer) {
      return Optional.empty();
    }
    Trip<L> trip = new Trip<>(player, nextId(active), destination, navigatorId, navigator, scheduler,
        anchor, TICK_PERIOD, this::untrack, liveSearch, guideSearch, live, liveIntervalMillis);
    active.add(trip);
    trip.start();
    return Optional.of(trip);
  }

  /** The smallest positive id not currently used by one of the player's active trips. */
  private int nextId(List<Trip<L>> active) {
    int id = 1;
    boolean taken = true;
    while (taken) {
      taken = false;
      for (Trip<L> trip : active) {
        if (trip.id() == id) {
          taken = true;
          id++;
          break;
        }
      }
    }
    return id;
  }

  /**
   * Stops and untracks every trip of a player whose destination matches (case-insensitively) — used
   * to replace an existing trip when the player re-navigates to the same place.
   *
   * @param player the player id
   * @param destination the destination label
   * @return how many trips were replaced
   */
  public synchronized int cancelByDestination(UUID player, String destination) {
    List<Trip<L>> active = byPlayer.get(player);
    if (active == null) {
      return 0;
    }
    List<Trip<L>> matches = new ArrayList<>();
    for (Trip<L> trip : active) {
      if (trip.destination().equalsIgnoreCase(destination)) {
        matches.add(trip);
      }
    }
    for (Trip<L> trip : matches) {
      trip.stop();
      untrack(trip);
    }
    return matches.size();
  }

  /**
   * Stops and untracks a single trip by its per-player id.
   *
   * @param player the player id
   * @param id the trip id
   * @return {@code true} if a trip with that id existed
   */
  public synchronized boolean cancel(UUID player, int id) {
    List<Trip<L>> active = byPlayer.get(player);
    if (active == null) {
      return false;
    }
    for (Trip<L> trip : active) {
      if (trip.id() == id) {
        trip.stop();
        untrack(trip);
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the player's active trips.
   *
   * @param player the player id
   * @return the active trips (a copy; never {@code null})
   */
  public synchronized List<Trip<L>> trips(UUID player) {
    return List.copyOf(byPlayer.getOrDefault(player, List.of()));
  }

  /**
   * Stops and untracks a single trip.
   *
   * @param trip the trip to stop
   */
  public synchronized void stop(Trip<L> trip) {
    trip.stop();
    untrack(trip);
  }

  /**
   * Stops and untracks all of a player's trips (call on logout).
   *
   * @param player the player id
   */
  public synchronized void stopAll(UUID player) {
    List<Trip<L>> active = byPlayer.remove(player);
    if (active != null) {
      active.forEach(Trip::stop);
    }
  }

  /**
   * Returns the total number of active trips across all players (for metrics).
   *
   * @return the active trip count
   */
  public synchronized int activeCount() {
    return byPlayer.values().stream().mapToInt(List::size).sum();
  }

  /** Stops every trip for every player (call on plugin disable). */
  public synchronized void stopEverything() {
    byPlayer.values().forEach(active -> active.forEach(Trip::stop));
    byPlayer.clear();
  }

  private synchronized void untrack(Trip<L> trip) {
    List<Trip<L>> active = byPlayer.get(trip.player());
    if (active != null) {
      active.remove(trip);
      if (active.isEmpty()) {
        byPlayer.remove(trip.player());
      }
    }
  }
}
