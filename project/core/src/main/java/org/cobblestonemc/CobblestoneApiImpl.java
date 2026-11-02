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
 * SearchScheduler}.
 *
 * <p>The {@link HeuristicStrategy} is supplied at construction (a single instance serves all
 * searches, since it is domain-type-agnostic). It defaults to {@link Heuristics#zero()}; supply
 * {@link Heuristics#euclidean(double)} for informed search once a per-block cost lower bound is
 * known. (Per-agent cost floors are a later, Minecraft-layer concern.)
 */
public final class CobblestoneApiImpl implements CobblestoneApi {

  @Override
  public <T, D extends Domain> SearchHandle<Position<D>, T> navigate(
      CobblestoneLogger logger,
      SearchScheduler scheduler,
      Position<D> origin,
      Destination<DomainRegion<D>> destination,
      MovementBehavior<T, D> behavior,
      List<? extends Transition<T, D>> transitions,
      List<? extends Restriction<D>> restrictions,
      HeuristicStrategy heuristic,
      SearchSettings settings,
      SearchObserver observer) {
    SearchImpl<T, D> search =
        new SearchImpl<>(
            logger,
            scheduler,
            heuristic,
            origin,
            destination,
            behavior,
            transitions,
            restrictions,
            settings,
            observer);
    search.start();
    return search;
  }
}
