/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.destination;

import java.util.Map;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.plugin.api.DestinationTree;
import net.whimxiqal.odyssey.plugin.api.MinecraftDestination;

/**
 * A plain, immutable {@link DestinationTree} node built from ready-made child maps. Children stay
 * behind {@link Supplier}s so a provider can defer materializing large sets (every waypoint, every
 * town) until a node is actually visited during command traversal or tab-completion.
 *
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public final class SimpleDestinationTree<W, V> implements DestinationTree<W, V> {

  private final String key;
  private final boolean strict;
  private final Map<String, Supplier<? extends DestinationTree<W, V>>> subTrees;
  private final Map<String, Supplier<MinecraftDestination<W, V>>> destinations;

  /**
   * Creates a node.
   *
   * @param key the node's key (unique among its siblings)
   * @param strict whether this level may never be omitted in commands
   * @param subTrees the child sub-trees, keyed by key
   * @param destinations the leaf destinations, keyed by key
   */
  public SimpleDestinationTree(
      String key,
      boolean strict,
      Map<String, Supplier<? extends DestinationTree<W, V>>> subTrees,
      Map<String, Supplier<MinecraftDestination<W, V>>> destinations) {
    this.key = key;
    this.strict = strict;
    this.subTrees = Map.copyOf(subTrees);
    this.destinations = Map.copyOf(destinations);
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public boolean strict() {
    return strict;
  }

  @Override
  public Map<String, Supplier<? extends DestinationTree<W, V>>> subTrees() {
    return subTrees;
  }

  @Override
  public Map<String, Supplier<MinecraftDestination<W, V>>> destinations() {
    return destinations;
  }
}
