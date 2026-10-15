/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.List;
import java.util.ServiceLoader;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;

/**
 * The generic, Minecraft-agnostic navigation service.
 *
 * <p>Given an origin, a destination, and the movement and transitions available, it runs a two-tier
 * search asynchronously and returns a {@link SearchHandle}. Downstream façades bind the type
 * parameters to concrete types so end users never see a generic.
 */
public interface CobblestoneApi {

  static CobblestoneApi load() {
    // Use the interface's own classloader (the plugin classloader when core is shaded into the
    // plugin jar) rather than the thread-context classloader, which is not reliably the plugin's
    // classloader inside a Paper plugin's onEnable.
    return ServiceLoader.load(CobblestoneApi.class, CobblestoneApi.class.getClassLoader())
        .findFirst()
        .orElseThrow();
  }

  /**
   * Begins a search from {@code origin} toward {@code destination}.
   *
   * <p>The observer is for recording a solve so it can be looked at afterwards. It must not affect
   * the search, and recording costs real time, so a run being measured passes {@link
   * SearchObserver#none()}.
   *
   * @param logger the logger to track logging events
   * @param scheduler the scheduler of tasks for the algorithm to parallelize work
   * @param origin the starting position
   * @param destination the goal
   * @param behavior how to move from cell to cell
   * @param transitions the transitions (portals, teleports, mounts, …) available
   * @param restrictions the passability restrictions barring certain cells
   * @param heuristic the heuristic to use for approaching the destination
   * @param settings the search limits and knobs
   * @param observer receives what the search does; {@link SearchObserver#none()} for nothing
   * @param <T> the payload type
   * @param <D> the domain type
   * @return a handle to the in-flight search
   */
  <T, D extends Domain> SearchHandle<Position<D>, T> navigate(
      CobblestoneLogger logger,
      Scheduler scheduler,
      Position<D> origin,
      Destination<DomainRegion<D>> destination,
      MovementBehavior<T, D> behavior,
      List<? extends Transition<T, D>> transitions,
      List<? extends Restriction<D>> restrictions,
      HeuristicStrategy heuristic,
      SearchSettings settings,
      SearchObserver observer);
}
