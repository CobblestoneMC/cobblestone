/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.List;

/**
 * The result of a {@link Graph} shortest-path query: an alternating sequence <i>node, edge, node,
 * …, node</i>.
 *
 * <p>{@code nodes} always has exactly one more element than {@code edges}: {@code edges.get(i)}
 * connects {@code nodes.get(i)} to {@code nodes.get(i + 1)}.
 *
 * @param <N> the node type
 * @param <E> the edge type
 * @param nodes the ordered nodes, source first and goal last (size {@code edges.size() + 1})
 * @param edges the ordered edges between consecutive nodes
 */
public record GraphPath<N, E>(List<N> nodes, List<E> edges) {

  /**
   * Constructor for GraphPath, which copies the nodes and edges.
   *
   * @param nodes the nodes
   * @param edges the edges
   */
  public GraphPath {
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
    if (nodes.size() != edges.size() + 1) {
      throw new IllegalArgumentException(
          "nodes must have exactly one more element than edges: "
              + nodes.size()
              + " vs "
              + edges.size());
    }
  }
}
