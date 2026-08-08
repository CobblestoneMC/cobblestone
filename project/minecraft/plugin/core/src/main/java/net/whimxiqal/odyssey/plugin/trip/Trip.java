/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.trip;

import java.util.UUID;
import java.util.function.Consumer;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.ScheduledTaskHandle;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.plugin.api.Navigator;

/**
 * One active guided journey: it owns a {@link Navigator} and ticks it on the scheduler until the
 * navigator reports completion or the trip is stopped (cancellation or logout). Platform-neutral —
 * the navigator supplies all platform-specific rendering. Created and tracked by {@link TripManager}.
 *
 * @param <L> the native location type the navigator renders in
 */
public final class Trip<L> {

  private final UUID player;
  private final int id;
  private final String destination;
  private final String navigatorId;
  private final Navigator<L> navigator;
  private final MinecraftScheduler scheduler;
  private final Position<? extends MinecraftWorld> anchor;
  private final long periodTicks;
  private final Consumer<Trip<L>> onEnd;
  private final LiveSearch<L> liveSearch;
  private final long liveIntervalMillis;

  private ScheduledTaskHandle handle;
  private volatile boolean stopped;

  Trip(
      UUID player,
      int id,
      String destination,
      String navigatorId,
      Navigator<L> navigator,
      MinecraftScheduler scheduler,
      Position<? extends MinecraftWorld> anchor,
      long periodTicks,
      Consumer<Trip<L>> onEnd,
      LiveSearch<L> liveSearch,
      long liveIntervalMillis) {
    this.player = player;
    this.id = id;
    this.destination = destination;
    this.navigatorId = navigatorId;
    this.navigator = navigator;
    this.scheduler = scheduler;
    this.anchor = anchor;
    this.periodTicks = periodTicks;
    this.onEnd = onEnd;
    this.liveSearch = liveSearch;
    this.liveIntervalMillis = liveIntervalMillis;
  }

  void start() {
    navigator.start();
    handle = scheduler.runAtPositionRepeating(anchor, this::tick, periodTicks);
    if (liveSearch != null) {
      scheduleReSearch();
    }
  }

  private void scheduleReSearch() {
    scheduler.runAsyncLater(this::reSearch, liveIntervalMillis);
  }

  private void reSearch() {
    if (stopped) {
      return;
    }
    liveSearch.search().whenComplete((result, error) -> {
      if (stopped) {
        return;
      }
      if (error == null && result != null) {
        result.ifPresent(this::applyNewPath);
      }
      scheduleReSearch(); // keep recalculating until the trip stops
    });
  }

  private void applyNewPath(Path<Step<L, MinecraftStepPayload>> path) {
    // Hot-swap on the render thread so it never races the navigator's tick.
    scheduler.runAtPosition(anchor, () -> {
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
  public UUID player() {
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
