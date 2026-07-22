/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Tier-1 graph over {@link Transition}s: nodes are "at a transition's
 * destination in some
 * state", edges are {@link VirtualPath}s (a same-domain hop to another
 * transition's origin region)
 * followed by traversing that transition.
 *
 * <p>
 * Edges are produced lazily and their {@link VirtualPath}s are memoized so
 * their solve results
 * persist across Dijkstra re-plans. Bookends
 * connect the player's origin
 * and the goal regions (via a super-sink).
 *
 * @param <T> the payload type
 * @param <D> the domain type
 */
final class Tier1Graph<T, D extends Domain>
    extends Graph<Tier1Node<T, D>, Tier1Edge<T, D>> {

  private final HeuristicStrategy heuristic;
  private final Map<Tier1Node<T, D>, Iterable<Tier1Edge<T, D>>> edgeMap = new HashMap<>();
  private final Map<D, List<Transition<T, D>>> transitionsByOriginDomain = new HashMap<>();
  private final Map<D, List<DomainRegion<D>>> destinationsByDomain = new HashMap<>();
  private final Tier1Node.Source<T, D> originNode;

  Tier1Graph(
      Position<D> origin,
      List<? extends Transition<T, D>> transitions,
      Collection<? extends DomainRegion<D>> destinationRegions,
      HeuristicStrategy heuristic) {
    this.heuristic = heuristic;
    this.originNode = new Tier1Node.Source<>(origin, TraversalState.DEFAULT);
    for (Transition<T, D> transition : transitions) {
      transitionsByOriginDomain.computeIfAbsent(transition.origin().domain(), key -> new ArrayList<>()).add(transition);
    }
    for (DomainRegion<D> region : destinationRegions) {
      destinationsByDomain.computeIfAbsent(region.domain(), key -> new ArrayList<>()).add(region);
    }
  }

  Tier1Node.Source<T, D> originNode() {
    return originNode;
  }

  boolean isGoal(Tier1Node<T, D> node) {
    return node instanceof Tier1Node.Sink<T, D>;
  }

  @Override
  protected Iterable<Tier1Edge<T, D>> outboundEdges(Tier1Node<T, D> node) {
    if (edgeMap.containsKey(node)) {
      // already cached
      return edgeMap.get(node);
    }
    Iterable<Tier1Edge<T, D>> edges;
    switch (node) {
      case Tier1Node.Source<T, D> sourceNode -> {
        edges = computeEdges(sourceNode.position(), sourceNode.state());
      }
      case Tier1Node.AtTransition<T, D> transitionNode -> {
        edges = computeEdges(transitionNode.transition().destination(), transitionNode.state());
      }
      case Tier1Node.Sink<T, D> sinkNode -> {
        return List.of();
      }
    }
    edgeMap.put(node, edges);
    return edges;
  }

  private Iterable<Tier1Edge<T, D>> computeEdges(Position<D> position, TraversalState state) {
    D destinationDomain = position.domain();
    Cell fromCell = position.cell();
    List<Transition<T, D>> transitionTargets = transitionsByOriginDomain.getOrDefault(destinationDomain, List.of());
    List<DomainRegion<D>> destinationTargets = destinationsByDomain.getOrDefault(destinationDomain, List.of());
    List<Tier1Edge<T, D>> edges = new ArrayList<>(transitionTargets.size() + destinationTargets.size());
    for (Transition<T, D> target : transitionTargets) {
      edges.add(
          new Tier1Edge<T, D>(
              new VirtualPath<T, D>(fromCell, destinationDomain, target.origin(), state),
              new Tier1Node.AtTransition<>(target, target.apply(state)),
              state));
    }
    for (DomainRegion<D> target : destinationTargets) {
      edges.add(
          new Tier1Edge<T, D>(
              new VirtualPath<T, D>(fromCell, destinationDomain, target, state),
              new Tier1Node.Sink<>(),
              state));
    }
    return edges;
  }

  @Override
  protected Tier1Node<T, D> head(Tier1Edge<T, D> edge) {
    return edge.target();
  }

  @Override
  protected double cost(Tier1Edge<T, D> edge) {
    return edge.virtualPath().cost(heuristic) + edge.target().cost();
  }

}
