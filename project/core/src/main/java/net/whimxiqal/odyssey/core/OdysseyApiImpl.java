/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import java.util.List;
import java.util.Objects;
import net.whimxiqal.odyssey.api.Agent;
import net.whimxiqal.odyssey.api.Destination;
import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.Mode;
import net.whimxiqal.odyssey.api.OdysseyApi;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.Scheduler;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.api.Transition;

/**
 * The default {@link OdysseyApi} implementation, running the two-tier search on a {@link Scheduler}.
 *
 * <p>The {@link HeuristicStrategy} is supplied at construction (a single instance serves all
 * searches, since it is domain-type-agnostic). It defaults to {@link Heuristics#zero()}; supply
 * {@link Heuristics#euclidean(double)} for informed search once a per-block cost lower bound is
 * known. (Per-agent cost floors are a later, Minecraft-layer concern.)
 */
public final class OdysseyApiImpl implements OdysseyApi {

  private final Scheduler scheduler;
  private final HeuristicStrategy heuristic;

  /**
   * Creates an instance using the uninformed {@link Heuristics#zero()} heuristic.
   *
   * @param scheduler the scheduler that runs searches
   */
  public OdysseyApiImpl(Scheduler scheduler) {
    this(scheduler, Heuristics.zero());
  }

  /**
   * Creates an instance with an explicit heuristic.
   *
   * @param scheduler the scheduler that runs searches
   * @param heuristic the heuristic used for Tier-1 estimates and Tier-2 A*
   */
  public OdysseyApiImpl(Scheduler scheduler, HeuristicStrategy heuristic) {
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.heuristic = Objects.requireNonNull(heuristic, "heuristic");
  }

  @Override
  public <A extends Agent, T extends Enum<T>, I, D extends Domain> SearchHandle<T, I, D> navigate(
      A agent,
      Position<D> origin,
      Destination<D> destination,
      List<? extends Mode<A, T, I, D>> modes,
      List<? extends Transition<T, I, D>> transitions,
      SearchSettings settings) {
    SearchImpl<A, T, I, D> search = new SearchImpl<>(
        scheduler, heuristic, agent, origin, destination, modes, transitions, settings);
    search.start();
    return search;
  }
}
