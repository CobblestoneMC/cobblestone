/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * A fluent builder for a {@link PlatformDestinationTree} node — so an integration writes its {@code
 * /navigate} structure as structure, not nested {@code Map<String, Supplier<…>>} plumbing.
 *
 * <p>Children are held behind {@link Supplier}s, so huge sets (every town, every home) are not
 * materialized until a node is actually visited. A key may be both a {@code leaf} and a {@code
 * subtree} — Cobblestone treats "the node itself" as the leaf and "under the node" as the subtree.
 */
public final class DestinationTree {

  private boolean strict;
  private final Map<String, Supplier<PlatformDestinationTree<ServerWorld, Vector3i>>> subTrees =
      new LinkedHashMap<>();
  private final Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> destinations =
      new LinkedHashMap<>();

  private DestinationTree() {}

  /** Begins a node. Its key is chosen by whoever attaches it — see {@link #subtree}. */
  public static DestinationTree builder() {
    return new DestinationTree();
  }

  public static Map<String, Supplier<PlatformDestinationTree<ServerWorld, Vector3i>>>
      emptySubTrees() {
    return new LinkedHashMap<>();
  }

  public static Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> emptyLeaves() {
    return new LinkedHashMap<>();
  }

  /** Marks this level strict — it may never be omitted in commands (no name-promotion). */
  public DestinationTree strict() {
    this.strict = true;
    return this;
  }

  /** Adds a leaf destination, built on demand. */
  public DestinationTree leaf(
      String key, Supplier<MinecraftDestination<ServerWorld, Vector3i>> destination) {
    destinations.put(key, destination);
    return this;
  }

  /** Adds a leaf destination. */
  public DestinationTree leaf(String key, MinecraftDestination<ServerWorld, Vector3i> destination) {
    return leaf(key, () -> destination);
  }

  /** Adds a child sub-tree, built on demand. */
  public DestinationTree subtree(
      String key, Supplier<PlatformDestinationTree<ServerWorld, Vector3i>> tree) {
    subTrees.put(key, tree);
    return this;
  }

  /** Adds a child sub-tree from another builder, under the given key. */
  public DestinationTree subtree(String key, DestinationTree child) {
    return subtree(key, child::build);
  }

  /** Builds the immutable tree node. */
  public PlatformDestinationTree<ServerWorld, Vector3i> build() {
    return new Node(strict, Map.copyOf(subTrees), Map.copyOf(destinations));
  }

  private record Node(
      boolean strict,
      Map<String, Supplier<? extends PlatformDestinationTree<ServerWorld, Vector3i>>> subTrees,
      Map<String, Supplier<MinecraftDestination<ServerWorld, Vector3i>>> destinations)
      implements PlatformDestinationTree<ServerWorld, Vector3i> {}
}
