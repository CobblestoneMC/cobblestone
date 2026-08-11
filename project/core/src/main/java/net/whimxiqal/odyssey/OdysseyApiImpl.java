/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.*;

import java.util.List;

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
  public <A extends Agent, T, D extends Domain> SearchHandle<Position<D>, T> navigate(
          OdysseyLogger logger,
          Scheduler scheduler,
      A agent,
      Position<D> origin,
      Destination<DomainRegion<D>> destination,
      List<? extends Mode<A, T, D>> modes,
      List<? extends Transition<T, D>> transitions,
      List<? extends Restriction<A, D>> restrictions,
      HeuristicStrategy heuristic,
      SearchSettings settings) {
    SearchImpl<A, T, D> search = new SearchImpl<>(
        logger, scheduler, heuristic, agent, origin, destination, modes, transitions, restrictions,
        settings);
    search.start();
    return search;
  }
}
