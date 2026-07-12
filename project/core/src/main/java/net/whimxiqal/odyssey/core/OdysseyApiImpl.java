/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import java.util.List;

import net.whimxiqal.odyssey.api.*;

/**
 * The default {@link OdysseyApi} implementation, running the two-tier search on a {@link Scheduler}.
 *
 * <p>The {@link HeuristicStrategy} is supplied at construction (a single instance serves all
 * searches, since it is domain-type-agnostic). It defaults to {@link Heuristics#zero()}; supply
 * {@link Heuristics#euclidean(double)} for informed search once a per-block cost lower bound is
 * known. (Per-agent cost floors are a later, Minecraft-layer concern.)
 */
public final class OdysseyApiImpl implements OdysseyApi {

  @Override
  public <A extends Agent, T extends Enum<T>, I, D extends Domain> SearchHandle<Step<Position<D>, T, I>> navigate(
          Scheduler scheduler,
      A agent,
      Position<D> origin,
      Destination<D> destination,
      List<? extends Mode<A, T, I, D>> modes,
      List<? extends Transition<T, I, D>> transitions,
      HeuristicStrategy heuristic,
      SearchSettings settings) {
    SearchImpl<A, T, I, D> search = new SearchImpl<>(
        scheduler, heuristic, agent, origin, destination, modes, transitions, settings);
    search.start();
    return search;
  }
}
