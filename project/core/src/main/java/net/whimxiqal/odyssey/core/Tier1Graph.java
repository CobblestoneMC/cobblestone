/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.DomainRegion;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.api.Transition;

/**
 * The Tier-1 graph over {@link Transition}s: nodes are "at a transition's destination in some
 * state", edges are {@link VirtualPath}s (a same-domain hop to another transition's origin region)
 * followed by traversing that transition.
 *
 * <p>Edges are produced lazily and their {@link VirtualPath}s are memoized so their solve results
 * persist across Dijkstra re-plans. Bookend {@link SyntheticTransition}s connect the player's origin
 * and the goal regions (via a super-sink).
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
final class Tier1Graph<T extends Enum<T>, I, D extends Domain>
    extends Graph<Tier1Node<T, I, D>, Tier1Edge<T, I, D>> {

  private final HeuristicStrategy heuristic;
  private final Map<D, List<Transition<T, I, D>>> byOriginDomain = new HashMap<>();
  private final Set<Transition<T, I, D>> destinationTransitions = new HashSet<>();
  private final Map<VpKey<T, I, D>, VirtualPath<T, I, D>> memo = new HashMap<>();
  private final Tier1Node.AtTransition<T, I, D> originNode;
  private final Tier1Node.Sink<T, I, D> sink = new Tier1Node.Sink<>();

  Tier1Graph(
      Position<D> origin,
      List<? extends Transition<T, I, D>> transitions,
      Collection<? extends DomainRegion<D>> destinationRegions,
      HeuristicStrategy heuristic) {
    this.heuristic = heuristic;
    SyntheticTransition<T, I, D> originTransition = SyntheticTransition.origin(origin);
    this.originNode = new Tier1Node.AtTransition<>(originTransition, TraversalState.DEFAULT);
    for (Transition<T, I, D> transition : transitions) {
      index(transition);
    }
    for (DomainRegion<D> region : destinationRegions) {
      SyntheticTransition<T, I, D> destination = SyntheticTransition.destination(region);
      destinationTransitions.add(destination);
      index(destination);
    }
  }

  private void index(Transition<T, I, D> transition) {
    byOriginDomain.computeIfAbsent(transition.origin().domain(), key -> new ArrayList<>()).add(transition);
  }

  Tier1Node.AtTransition<T, I, D> originNode() {
    return originNode;
  }

  boolean isGoal(Tier1Node<T, I, D> node) {
    return node instanceof Tier1Node.Sink<T, I, D>;
  }

  @Override
  protected Iterable<Tier1Edge<T, I, D>> outboundEdges(Tier1Node<T, I, D> node) {
    switch (node) {
      case Tier1Node.Sink<T, I, D> ignored -> {
        return List.of();
      }
      case Tier1Node.AtTransition<T, I, D> at -> {
        if (destinationTransitions.contains(at.transition())) {
          return List.<Tier1Edge<T, I, D>>of(new Tier1Edge.SinkEdge<>());
        }
        Position<D> destination = at.transition().destination();
        D destinationDomain = destination.domain();
        Cell fromCell = destination.cell();
        List<Transition<T, I, D>> targets = byOriginDomain.getOrDefault(destinationDomain, List.of());
        List<Tier1Edge<T, I, D>> edges = new ArrayList<>(targets.size());
        for (Transition<T, I, D> target : targets) {
          VpKey<T, I, D> key = new VpKey<>(at.transition(), target, at.state());
          VirtualPath<T, I, D> virtualPath = memo.computeIfAbsent(
              key, ignored -> new VirtualPath<>(fromCell, destinationDomain, target.origin(), at.state()));
          edges.add(new Tier1Edge.VirtualPathEdge<>(virtualPath, target, at.state()));
        }
        return edges;
      }
    }
  }

  @Override
  protected Tier1Node<T, I, D> head(Tier1Edge<T, I, D> edge) {
    return switch (edge) {
      case Tier1Edge.SinkEdge<T, I, D> ignored -> sink;
      case Tier1Edge.VirtualPathEdge<T, I, D> vpe -> new Tier1Node.AtTransition<>(
          vpe.targetTransition(), vpe.targetTransition().apply(vpe.sourceState()));
    };
  }

  @Override
  protected double cost(Tier1Edge<T, I, D> edge) {
    return switch (edge) {
      case Tier1Edge.SinkEdge<T, I, D> ignored -> 0.0;
      case Tier1Edge.VirtualPathEdge<T, I, D> vpe ->
          vpe.virtualPath().cost(heuristic) + vpe.targetTransition().cost();
    };
  }

  /** Memo key for a virtual path: its source transition, target transition, and source state. */
  private record VpKey<T extends Enum<T>, I, D extends Domain>(
      Transition<T, I, D> source, Transition<T, I, D> target, TraversalState state) {
  }
}
