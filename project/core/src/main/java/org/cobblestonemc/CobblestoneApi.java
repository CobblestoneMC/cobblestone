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
 * <p>Given an origin, a destination, and the modes and transitions available to an agent, it runs a
 * two-tier search asynchronously and returns a {@link SearchHandle}. The five type parameters are
 * verbose here, but downstream façades bind them all to concrete types (e.g. {@code
 * CobblestonePlayer}, {@code MinecraftStepType}, {@code MinecraftInstruction}, {@code
 * CobblestoneWorld}) so end users never see a generic.
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
   * @param logger the logger to track logging events
   * @param scheduler the scheduler of tasks for the algorithm to parallelize work
   * @param agent the navigating agent
   * @param origin the starting position
   * @param destination the goal
   * @param modes provides the transportation modes available to the agent for a leg (given its
   *     target region, for goal-aware modes)
   * @param transitions the transitions (portals, teleports, mounts, …) available to the agent
   * @param restrictions the passability restrictions barring the agent from certain cells
   * @param heuristic the heuristic to use for approaching the destination
   * @param settings the search limits and knobs
   * @param <A> the agent type
   * @param <T> the payload type
   * @param <D> the domain type
   * @return a handle to the in-flight search
   */
  default <A extends Agent, T, D extends Domain> SearchHandle<Position<D>, T> navigate(
      CobblestoneLogger logger,
      Scheduler scheduler,
      A agent,
      Position<D> origin,
      Destination<DomainRegion<D>> destination,
      ModesProvider<A, T, D> modes,
      List<? extends Transition<T, D>> transitions,
      List<? extends Restriction<A, D>> restrictions,
      HeuristicStrategy heuristic,
      SearchSettings settings) {
    return navigate(
        logger,
        scheduler,
        agent,
        origin,
        destination,
        modes,
        transitions,
        restrictions,
        heuristic,
        settings,
        SearchObserver.none());
  }

  /**
   * Begins a search, reporting what it does to an observer.
   *
   * <p>For recording a solve so it can be replayed and looked at afterwards. An observer must not
   * affect the search, and recording one costs real time, so a run being measured uses {@link
   * SearchObserver#none()} — which is what the other overload passes.
   *
   * @param logger the logger to track logging events
   * @param scheduler the scheduler of tasks for the algorithm to parallelize work
   * @param agent the navigating agent
   * @param origin the starting position
   * @param destination the goal
   * @param modes provides the transportation modes available to the agent for a leg
   * @param transitions the transitions (portals, teleports, mounts, …) available to the agent
   * @param restrictions the passability restrictions barring the agent from certain cells
   * @param heuristic the heuristic to use for approaching the destination
   * @param settings the search limits and knobs
   * @param observer receives what the search does
   * @param <A> the agent type
   * @param <T> the payload type
   * @param <D> the domain type
   * @return a handle to the in-flight search
   */
  <A extends Agent, T, D extends Domain> SearchHandle<Position<D>, T> navigate(
      CobblestoneLogger logger,
      Scheduler scheduler,
      A agent,
      Position<D> origin,
      Destination<DomainRegion<D>> destination,
      ModesProvider<A, T, D> modes,
      List<? extends Transition<T, D>> transitions,
      List<? extends Restriction<A, D>> restrictions,
      HeuristicStrategy heuristic,
      SearchSettings settings,
      SearchObserver observer);
}
