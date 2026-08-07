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

  private final MinecraftScheduler scheduler;
  private final int maxActivePerPlayer;
  private final long tickPeriodTicks;
  private final Map<UUID, List<Trip<L>>> byPlayer = new ConcurrentHashMap<>();

  /**
   * Creates a trip manager.
   *
   * @param scheduler the scheduler trips tick on
   * @param maxActivePerPlayer the most simultaneous trips one player may run
   * @param tickPeriodTicks how often (in ticks) each trip re-renders
   */
  public TripManager(MinecraftScheduler scheduler, int maxActivePerPlayer, long tickPeriodTicks) {
    this.scheduler = scheduler;
    this.maxActivePerPlayer = maxActivePerPlayer;
    this.tickPeriodTicks = tickPeriodTicks;
  }

  /**
   * Starts a trip, unless the player is already at their trip limit.
   *
   * @param player the guided player's id
   * @param anchor the location whose owning thread the trip ticks on
   * @param navigatorId the navigator (display strategy) id
   * @param navigator the navigator to drive
   * @return the started trip, or empty if the player is at their limit
   */
  public synchronized Optional<Trip<L>> start(
      UUID player, Position<? extends MinecraftWorld> anchor, String navigatorId, Navigator<L> navigator) {
    return start(player, anchor, navigatorId, navigator, null, 0L);
  }

  private synchronized Optional<Trip<L>> start(
      UUID player, Position<? extends MinecraftWorld> anchor, String navigatorId,
      Navigator<L> navigator, LiveSearch<L> liveSearch, long liveIntervalMillis) {
    List<Trip<L>> active = byPlayer.computeIfAbsent(player, key -> new ArrayList<>());
    if (active.size() >= maxActivePerPlayer) {
      return Optional.empty();
    }
    Trip<L> trip = new Trip<>(player, navigatorId, navigator, scheduler, anchor, tickPeriodTicks,
        this::untrack, liveSearch, liveIntervalMillis);
    active.add(trip);
    trip.start();
    return Optional.of(trip);
  }

  /**
   * Starts a live trip that re-searches on an interval and hot-swaps the navigator's path, unless the
   * player is already at their trip limit.
   *
   * @param player the guided player's id
   * @param anchor the location whose owning thread the trip ticks on
   * @param navigatorId the navigator (display strategy) id
   * @param navigator the navigator to drive
   * @param liveSearch the re-search behavior
   * @param liveIntervalMillis the delay between re-searches, in milliseconds
   * @return the started trip, or empty if the player is at their limit
   */
  public synchronized Optional<Trip<L>> startLive(
      UUID player, Position<? extends MinecraftWorld> anchor, String navigatorId,
      Navigator<L> navigator, LiveSearch<L> liveSearch, long liveIntervalMillis) {
    return start(player, anchor, navigatorId, navigator, liveSearch, liveIntervalMillis);
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
