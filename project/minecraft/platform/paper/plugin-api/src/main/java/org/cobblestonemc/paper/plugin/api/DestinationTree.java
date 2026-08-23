/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.World;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.joml.Vector3i;

/**
 * A fluent builder for a {@link PlatformDestinationTree} node — so an integration writes its {@code
 * /navigate} structure as structure, not nested {@code Map<String, Supplier<…>>} plumbing:
 *
 * <pre>{@code
 * DestinationTree.builder()
 *     .subtree("town", townList)          // a lazily-built child
 *     .leaf("resident", Destination.at(spawn, "resident"));
 * }</pre>
 *
 * <p>Children are held behind {@link Supplier}s, so huge sets (every town, every home) are not
 * materialized until a node is actually visited. A key may be both a {@code leaf} and a {@code
 * subtree} — Cobblestone treats "the node itself" as the leaf and "under the node" as the subtree.
 */
public final class DestinationTree {

  private boolean strict;
  private final Map<String, Supplier<? extends PlatformDestinationTree<World, Vector3i>>> subTrees =
      new LinkedHashMap<>();
  private final Map<String, Supplier<MinecraftDestination<World, Vector3i>>> destinations =
      new LinkedHashMap<>();

  /** Begins a node. Its key is chosen by whoever attaches it — see {@link #subtree}. */
  public static DestinationTree builder() {
    return new DestinationTree();
  }

  /** Marks this level strict — it may never be omitted in commands (no name-promotion). */
  public DestinationTree strict() {
    this.strict = true;
    return this;
  }

  /** Adds a leaf destination, built on demand. */
  public DestinationTree leaf(
      String key, Supplier<MinecraftDestination<World, Vector3i>> destination) {
    destinations.put(key, destination);
    return this;
  }

  /** Adds a leaf destination. */
  public DestinationTree leaf(String key, MinecraftDestination<World, Vector3i> destination) {
    return leaf(key, () -> destination);
  }

  /** Adds a child sub-tree, built on demand. */
  public DestinationTree subtree(
      String key, Supplier<? extends PlatformDestinationTree<World, Vector3i>> tree) {
    subTrees.put(key, tree);
    return this;
  }

  /** Adds a child sub-tree from another builder, under the given key. */
  public DestinationTree subtree(String key, DestinationTree child) {
    return subtree(key, child::build);
  }

  /** Builds the immutable tree node. */
  public PlatformDestinationTree<World, Vector3i> build() {
    return new Node(strict, Map.copyOf(subTrees), Map.copyOf(destinations));
  }

  private record Node(
      boolean strict,
      Map<String, Supplier<? extends PlatformDestinationTree<World, Vector3i>>> subTrees,
      Map<String, Supplier<MinecraftDestination<World, Vector3i>>> destinations)
      implements PlatformDestinationTree<World, Vector3i> {}
}
