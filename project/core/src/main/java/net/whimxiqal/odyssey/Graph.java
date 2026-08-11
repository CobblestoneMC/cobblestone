/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * An abstract, reusable, unit-testable shortest-path engine over a lazily-described directed graph
 * with non-negative edge costs (Dijkstra's algorithm).
 *
 * <p>Subclasses describe the graph on demand via {@link #outboundEdges}, {@link #head}, and {@link
 * #cost} — nodes and edges are never enumerated up front, so an effectively-infinite graph
 * (Odyssey's Tier-1 transition graph) can be searched by materializing only the frontier actually
 * reached. Edge costs may be {@link Double#POSITIVE_INFINITY} to mark an edge as untraversable;
 * such edges are skipped.
 *
 * <p>Node type {@code N} must provide value-based {@code equals}/{@code hashCode}.
 *
 * @param <N> the node type
 * @param <E> the edge type
 */
public abstract class Graph<N, E> {

  /**
   * Returns the edges leaving {@code node}. Produced lazily; may be empty.
   *
   * @param node the node
   * @return its outbound edges
   */
  protected abstract Iterable<E> outboundEdges(N node);

  /**
   * Returns the destination node of {@code edge}.
   *
   * @param edge the edge
   * @return the node the edge points to
   */
  protected abstract N head(E edge);

  /**
   * Returns the non-negative cost of traversing {@code edge}, possibly {@link
   * Double#POSITIVE_INFINITY} to mark it untraversable.
   *
   * @param edge the edge
   * @return the edge cost
   */
  protected abstract double cost(E edge);

  /**
   * Finds a least-cost path from {@code source} to any node satisfying {@code isGoal}.
   *
   * @param source the start node
   * @param isGoal the goal predicate
   * @return the least-cost path, or empty if no goal is reachable
   */
  public Optional<GraphPath<N, E>> shortestPath(N source, Predicate<N> isGoal) {
    Map<N, Double> dist = new HashMap<>();
    Map<N, Backlink<N, E>> prev = new HashMap<>();
    PriorityQueue<Frontier<N>> queue =
        new PriorityQueue<>((a, b) -> Double.compare(a.dist(), b.dist()));

    dist.put(source, 0.0);
    queue.add(new Frontier<>(source, 0.0));

    while (!queue.isEmpty()) {
      Frontier<N> current = queue.poll();
      N node = current.node();
      // Lazy deletion: skip stale queue entries superseded by a cheaper path.
      if (current.dist() > dist.getOrDefault(node, Double.POSITIVE_INFINITY)) {
        continue;
      }
      if (isGoal.test(node)) {
        return Optional.of(reconstruct(source, node, prev));
      }
      for (E edge : outboundEdges(node)) {
        double edgeCost = cost(edge);
        if (Double.isInfinite(edgeCost) || Double.isNaN(edgeCost)) {
          continue;
        }
        N next = head(edge);
        double candidate = current.dist() + edgeCost;
        if (candidate < dist.getOrDefault(next, Double.POSITIVE_INFINITY)) {
          dist.put(next, candidate);
          prev.put(next, new Backlink<>(node, edge));
          queue.add(new Frontier<>(next, candidate));
        }
      }
    }
    return Optional.empty();
  }

  private GraphPath<N, E> reconstruct(N source, N goal, Map<N, Backlink<N, E>> prev) {
    Deque<N> nodes = new ArrayDeque<>();
    Deque<E> edges = new ArrayDeque<>();
    N cursor = goal;
    nodes.addFirst(cursor);
    while (!cursor.equals(source)) {
      Backlink<N, E> link = prev.get(cursor);
      edges.addFirst(link.edge());
      nodes.addFirst(link.from());
      cursor = link.from();
    }
    return new GraphPath<>(new ArrayList<>(nodes), new ArrayList<>(edges));
  }

  private record Frontier<N>(N node, double dist) {}

  private record Backlink<N, E>(N from, E edge) {}
}
