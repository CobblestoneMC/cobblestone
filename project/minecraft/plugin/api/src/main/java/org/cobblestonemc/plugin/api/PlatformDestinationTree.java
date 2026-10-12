/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.api;

import java.util.Map;
import java.util.function.Supplier;

/**
 * A lazily-evaluated tree of navigation targets. Sub-trees and leaves are keyed by unique strings
 * (upper-case allowed, no special characters; spaces are allowed but discouraged).
 *
 * <p>Sub-trees are exposed as {@link Supplier}s so that huge sets — every town, every player home —
 * are not materialized until the node is actually visited during command traversal or
 * tab-completion. Leaves only describe themselves until they are {@linkplain DestinationLeaf#build
 * built}, which happens once a player has picked one.
 *
 * @param <W> the platform world type
 * @param <V> the platform block-position type
 */
public interface PlatformDestinationTree<W, V> {

  /**
   * Returns whether this level is strict: a strict level may never be omitted in commands (it is
   * never a candidate for name-promotion).
   *
   * @return {@code true} if strict
   */
  boolean strict();

  /**
   * Returns the child sub-trees, keyed by their key, each behind a supplier.
   *
   * @return the sub-trees
   */
  Map<String, Supplier<? extends PlatformDestinationTree<W, V>>> subTrees();

  /**
   * Returns the leaves at this node, keyed by their key.
   *
   * @return the leaves
   */
  Map<String, DestinationLeaf<MinecraftDestination<W, V>>> destinations();
}
