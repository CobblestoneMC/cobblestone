/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;
import org.bukkit.World;
import org.joml.Vector3i;

/**
 * A fluent builder for a {@link DestinationTree} node — so an integration writes its {@code /navigate}
 * structure as structure, not nested {@code Map<String, Supplier<…>>} plumbing:
 * <pre>{@code
 * PaperDestinationTree.node("towny")
 *     .subtree("town", townList)          // a lazily-built child
 *     .leaf("resident", PaperDestination.at(spawn, "resident"));
 * }</pre>
 *
 * <p>Children are held behind {@link Supplier}s, so huge sets (every town, every home) are not
 * materialized until a node is actually visited. A key may be both a {@code leaf} and a {@code
 * subtree} — Odyssey treats "the node itself" as the leaf and "under the node" as the subtree.
 */
public final class PaperDestinationTree {

  private final String key;
  private boolean strict;
  private final Map<String, Supplier<? extends DestinationTree<World, Vector3i>>> subTrees =
      new LinkedHashMap<>();
  private final Map<String, Supplier<MinecraftDestination<World, Vector3i>>> destinations =
      new LinkedHashMap<>();

  private PaperDestinationTree(String key) {
    this.key = key;
  }

  /** Begins a node with the given key (unique among its siblings). */
  public static PaperDestinationTree node(String key) {
    return new PaperDestinationTree(key);
  }

  /** Marks this level strict — it may never be omitted in commands (no name-promotion). */
  public PaperDestinationTree strict() {
    this.strict = true;
    return this;
  }

  /** Adds a leaf destination, built on demand. */
  public PaperDestinationTree leaf(String key, Supplier<MinecraftDestination<World, Vector3i>> destination) {
    destinations.put(key, destination);
    return this;
  }

  /** Adds a leaf destination. */
  public PaperDestinationTree leaf(String key, MinecraftDestination<World, Vector3i> destination) {
    return leaf(key, () -> destination);
  }

  /** Adds a child sub-tree, built on demand. */
  public PaperDestinationTree subtree(String key, Supplier<? extends DestinationTree<World, Vector3i>> tree) {
    subTrees.put(key, tree);
    return this;
  }

  /** Adds a child sub-tree from another builder. */
  public PaperDestinationTree subtree(PaperDestinationTree child) {
    return subtree(child.key, child::build);
  }

  /** Builds the immutable tree node. */
  public DestinationTree<World, Vector3i> build() {
    return new Node(key, strict, Map.copyOf(subTrees), Map.copyOf(destinations));
  }

  private record Node(
      String key,
      boolean strict,
      Map<String, Supplier<? extends DestinationTree<World, Vector3i>>> subTrees,
      Map<String, Supplier<MinecraftDestination<World, Vector3i>>> destinations)
      implements DestinationTree<World, Vector3i> {
  }
}
