/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GraphTest {

  private record Edge(String to, double cost) {
  }

  /** A concrete {@link Graph} over an explicit adjacency map, for testing Dijkstra in isolation. */
  private static final class MapGraph extends Graph<String, Edge> {

    private final Map<String, List<Edge>> adjacency;

    MapGraph(Map<String, List<Edge>> adjacency) {
      this.adjacency = adjacency;
    }

    @Override
    protected Iterable<Edge> outboundEdges(String node) {
      return adjacency.getOrDefault(node, List.of());
    }

    @Override
    protected String head(Edge edge) {
      return edge.to();
    }

    @Override
    protected double cost(Edge edge) {
      return edge.cost();
    }
  }

  @Test
  void choosesCheapestPathNotFewestHops() {
    MapGraph graph = new MapGraph(Map.of(
        "A", List.of(new Edge("B", 1), new Edge("C", 4)),
        "B", List.of(new Edge("C", 1), new Edge("D", 5)),
        "C", List.of(new Edge("D", 1)),
        "D", List.of()));

    Optional<GraphPath<String, Edge>> path = graph.shortestPath("A", "D"::equals);

    assertTrue(path.isPresent());
    assertEquals(List.of("A", "B", "C", "D"), path.get().nodes());
    assertEquals(3.0, path.get().edges().stream().mapToDouble(Edge::cost).sum(), 1e-9);
  }

  @Test
  void skipsInfiniteEdges() {
    MapGraph graph = new MapGraph(Map.of(
        "A", List.of(new Edge("B", Double.POSITIVE_INFINITY), new Edge("C", 1)),
        "C", List.of(new Edge("D", 1)),
        "B", List.of(new Edge("D", 1)),
        "D", List.of()));

    Optional<GraphPath<String, Edge>> path = graph.shortestPath("A", "D"::equals);

    assertTrue(path.isPresent());
    assertEquals(List.of("A", "C", "D"), path.get().nodes());
  }

  @Test
  void returnsEmptyWhenGoalUnreachable() {
    MapGraph graph = new MapGraph(Map.of(
        "A", List.of(new Edge("B", 1)),
        "B", List.of()));

    assertTrue(graph.shortestPath("A", "Z"::equals).isEmpty());
  }

  @Test
  void sourceIsGoalYieldsSingletonPath() {
    MapGraph graph = new MapGraph(Map.of("A", List.of()));

    Optional<GraphPath<String, Edge>> path = graph.shortestPath("A", "A"::equals);

    assertTrue(path.isPresent());
    assertEquals(List.of("A"), path.get().nodes());
    assertTrue(path.get().edges().isEmpty());
  }
}
