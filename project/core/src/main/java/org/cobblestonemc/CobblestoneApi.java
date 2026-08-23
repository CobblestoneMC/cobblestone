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
   * @param agent the navigating agent
   * @param origin the starting position
   * @param destination the goal
   * @param modes provides the transportation modes available to the agent for a leg (given its
   *     target region, for goal-aware modes)
   * @param transitions the transitions (portals, teleports, mounts, …) available to the agent
   * @param restrictions the passability restrictions barring the agent from certain cells
   * @param settings the search limits and knobs
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
      SearchSettings settings);
}
