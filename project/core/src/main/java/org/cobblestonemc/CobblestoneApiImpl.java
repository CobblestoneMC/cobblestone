/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.List;
import org.cobblestonemc.api.Destination;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;

/**
 * The default {@link CobblestoneApi} implementation, running the two-tier search on a {@link
 * Scheduler}.
 *
 * <p>The {@link HeuristicStrategy} is supplied at construction (a single instance serves all
 * searches, since it is domain-type-agnostic). It defaults to {@link Heuristics#zero()}; supply
 * {@link Heuristics#euclidean(double)} for informed search once a per-block cost lower bound is
 * known. (Per-agent cost floors are a later, Minecraft-layer concern.)
 */
public final class CobblestoneApiImpl implements CobblestoneApi {

  @Override
  public <A extends Agent, T, D extends Domain> SearchHandle<Position<D>, T> navigate(
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
    SearchImpl<A, T, D> search =
        new SearchImpl<>(
            logger,
            scheduler,
            heuristic,
            agent,
            origin,
            destination,
            modes,
            transitions,
            restrictions,
            settings);
    search.start();
    return search;
  }
}
