/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.api;

import java.util.Map;
import java.util.function.Supplier;

/**
 * A lazily-evaluated tree of navigation targets. Sub-trees and leaves are keyed by unique strings
 * (upper-case allowed, no special characters; spaces are allowed but discouraged).
 *
 * <p>Children are exposed as {@link Supplier}s so that huge sets — every town, every player home —
 * are not materialized until the node is actually visited during command traversal or
 * tab-completion.
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
   * Returns the leaf destinations at this node, keyed by their key, each behind a supplier.
   *
   * @return the destinations
   */
  Map<String, Supplier<MinecraftDestination<W, V>>> destinations();
}
