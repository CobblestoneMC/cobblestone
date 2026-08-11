/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.trip;

import java.util.function.Consumer;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.ScheduledTaskHandle;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.plugin.api.Navigator;

/**
 * One active guided journey: it owns a {@link Navigator} and ticks it on the scheduler until the
 * navigator reports completion or the trip is stopped (cancellation or logout). Platform-neutral —
 * the navigator supplies all platform-specific rendering. Created and tracked by {@link
 * TripManager}.
 *
 * @param <L> the native location type the navigator renders in
 */
public final class Trip<E, P extends TripAgent<E>, L> {

  private final P player;
  private final int id;
  private final String destination;
  private final String navigatorId;
  private final Navigator<L> navigator;
  private final MinecraftScheduler<E> scheduler;
  private final long periodTicks;
  private final Consumer<Trip<E, P, L>> onEnd;
  private final LiveSearch<L> liveSearch;
  private final GuideSearch<L> guideSearch;
  private final boolean live;
  private final long liveIntervalMillis;

  private ScheduledTaskHandle handle;
  private volatile boolean stopped;

  Trip(
      P player,
      int id,
      String destination,
      String navigatorId,
      Navigator<L> navigator,
      MinecraftScheduler<E> scheduler,
      long periodTicks,
      Consumer<Trip<E, P, L>> onEnd,
      LiveSearch<L> liveSearch,
      GuideSearch<L> guideSearch,
      boolean live,
      long liveIntervalMillis) {
    this.player = player;
    this.id = id;
    this.destination = destination;
    this.navigatorId = navigatorId;
    this.navigator = navigator;
    this.scheduler = scheduler;
    this.periodTicks = periodTicks;
    this.onEnd = onEnd;
    this.liveSearch = liveSearch;
    this.guideSearch = guideSearch;
    this.live = live;
    this.liveIntervalMillis = liveIntervalMillis;
  }

  void start() {
    navigator.start();
    handle = scheduler.runAtEntityRepeating(player.entity(), this::tick, periodTicks);
    if (live && liveSearch != null) {
      scheduleReSearch();
    }
  }

  private void scheduleReSearch() {
    scheduler.runAsyncLater(() -> reSearch(true), liveIntervalMillis);
  }

  /**
   * Runs one re-search; {@code reschedule} continues the periodic live loop, off for a one-shot.
   */
  private void reSearch(boolean reschedule) {
    if (stopped || liveSearch == null) {
      return;
    }
    liveSearch
        .search()
        .whenComplete(
            (result, error) -> {
              if (stopped) {
                return;
              }
              if (error == null) {
                result.ifPresent(this::applyNewPath);
              }
              if (reschedule && !stopped) {
                scheduleReSearch();
              }
            });
  }

  private void applyNewPath(Path<L, MinecraftStepPayload> path) {
    // Hot-swap on the render thread so it never races the navigator's tick.
    scheduler.runAtEntity(
        player.entity(),
        () -> {
          if (!stopped) {
            navigator.update(path);
          }
        });
  }

  private void tick() {
    if (stopped) {
      return;
    }
    if (navigator.isComplete()) {
      stop();
      onEnd.accept(this); // ask the manager to untrack us
      return;
    }
    navigator.tick();
    if (navigator.consumeRecalcRequest()) {
      reSearch(false); // player strayed: one-shot recalculation from their current position
    }
    if (guideSearch != null) {
      navigator.consumeGuideRequest().ifPresent(this::runGuideSearch);
    }
  }

  private void runGuideSearch(L target) {
    guideSearch
        .search(target)
        .whenComplete(
            (result, error) -> {
              if (stopped || error != null || result.isEmpty()) {
                return;
              }
              result.ifPresent(
                  path ->
                      scheduler.runAtEntity(
                          player.entity(),
                          () -> {
                            if (!stopped) {
                              navigator.setGuidePath(
                                  path); // hand the short path to the navigator on the render
                              // thread
                            }
                          }));
            });
  }

  /** Stops rendering and releases the navigator's display state. Idempotent. */
  public void stop() {
    if (stopped) {
      return;
    }
    stopped = true;
    if (handle != null) {
      handle.cancel();
    }
    navigator.stop();
  }

  /**
   * Returns the guided player's id.
   *
   * @return the player id
   */
  public P player() {
    return player;
  }

  /**
   * Returns this trip's short per-player id (for {@code /odyssey cancel &lt;id&gt;}).
   *
   * @return the trip id
   */
  public int id() {
    return id;
  }

  /**
   * Returns the human label of this trip's destination (e.g. {@code "waypoint home"}), used for the
   * trips listing and same-destination replacement.
   *
   * @return the destination label
   */
  public String destination() {
    return destination;
  }

  /**
   * Returns the estimated remaining traversal time in seconds (delegated to the navigator).
   *
   * @return the remaining seconds
   */
  public double remainingSeconds() {
    return navigator.remainingSeconds();
  }

  /**
   * Returns the id of the navigator (display strategy) driving this trip.
   *
   * @return the navigator id
   */
  public String navigatorId() {
    return navigatorId;
  }

  /**
   * Returns the navigator driving this trip (for live hot-swaps via {@link Navigator#update}).
   *
   * @return the navigator
   */
  public Navigator<L> navigator() {
    return navigator;
  }
}
