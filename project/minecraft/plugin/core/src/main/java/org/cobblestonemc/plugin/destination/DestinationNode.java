/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;

/**
 * One level of the destination tree the {@code /navigate} command walks — the shape of a {@link
 * PlatformDestinationTree}, over any destination type. The command's tree holds {@link
 * NavigationTarget}s; the resolver itself does not care what the leaves build.
 *
 * @param <D> what the leaves build
 */
public interface DestinationNode<D> {

  /**
   * Returns whether this level is strict: it may never be omitted in commands.
   *
   * @return {@code true} if strict
   */
  boolean strict();

  /**
   * Returns the child levels, keyed by their key, each behind a supplier so a large one is only
   * built when it is visited.
   *
   * @return the children
   */
  Map<String, Supplier<? extends DestinationNode<D>>> subTrees();

  /**
   * Returns the leaves at this level, keyed by their key.
   *
   * @return the leaves
   */
  Map<String, DestinationLeaf<D>> leaves();

  /**
   * Begins a level.
   *
   * @param <D> what the leaves build
   * @return a new, empty builder
   */
  static <D> Builder<D> builder() {
    return new Builder<>();
  }

  /**
   * A plain, immutable level.
   *
   * @param strict whether it may never be omitted
   * @param subTrees the children
   * @param leaves the leaves
   * @param <D> what the leaves build
   */
  record Simple<D>(
      boolean strict,
      Map<String, Supplier<? extends DestinationNode<D>>> subTrees,
      Map<String, DestinationLeaf<D>> leaves)
      implements DestinationNode<D> {

    /** Canonical constructor; copies the maps. */
    public Simple {
      subTrees = Map.copyOf(subTrees);
      leaves = Map.copyOf(leaves);
    }
  }

  /**
   * Builds a {@link DestinationNode}, keeping children in the order they are added.
   *
   * @param <D> what the leaves build
   */
  final class Builder<D> {

    private boolean strict;
    private final Map<String, Supplier<? extends DestinationNode<D>>> subTrees =
        new LinkedHashMap<>();
    private final Map<String, DestinationLeaf<D>> leaves = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Marks the level strict.
     *
     * @return this builder
     */
    public Builder<D> strict() {
      this.strict = true;
      return this;
    }

    /**
     * Adds a child level, built when it is visited.
     *
     * @param key its key
     * @param subTree builds it
     * @return this builder
     */
    public Builder<D> subtree(String key, Supplier<? extends DestinationNode<D>> subTree) {
      subTrees.put(key, subTree);
      return this;
    }

    /**
     * Adds a leaf.
     *
     * @param key its key
     * @param leaf the leaf
     * @return this builder
     */
    public Builder<D> leaf(String key, DestinationLeaf<D> leaf) {
      leaves.put(key, leaf);
      return this;
    }

    /**
     * Builds the level.
     *
     * @return the level
     */
    public DestinationNode<D> build() {
      return new Simple<>(strict, subTrees, leaves);
    }
  }
}
