/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

import java.util.List;

/**
 * The generic, Minecraft-agnostic navigation service.
 *
 * <p>Given an origin, a destination, and the modes and transitions available to an agent, it runs
 * a two-tier search asynchronously and returns a {@link SearchHandle}. The five type parameters are
 * verbose here, but downstream façades bind them all to concrete types (e.g. {@code OdysseyPlayer},
 * {@code MinecraftStepType}, {@code MinecraftInstruction}, {@code OdysseyWorld}) so end users never
 * see a generic.
 */
public interface OdysseyApi {

  /**
   * Begins a search from {@code origin} toward {@code destination}.
   *
   * @param agent the navigating agent
   * @param origin the starting position
   * @param destination the goal
   * @param modes the transportation modes available to the agent
   * @param transitions the transitions (portals, teleports, mounts, …) available to the agent
   * @param settings the search limits and knobs
   * @param <A> the agent type
   * @param <T> the step-type enum
   * @param <I> the instruction payload type
   * @param <D> the domain type
   * @return a handle to the in-flight search
   */
  <A extends Agent, T extends Enum<T>, I, D extends Domain> SearchHandle<T, I, D> navigate(
      A agent,
      Position<D> origin,
      Destination<D> destination,
      List<? extends Mode<A, T, I, D>> modes,
      List<? extends Transition<T, I, D>> transitions,
      SearchSettings settings);
}
