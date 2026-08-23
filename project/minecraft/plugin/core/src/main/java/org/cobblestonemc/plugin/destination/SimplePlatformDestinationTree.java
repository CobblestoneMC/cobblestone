/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.Map;
import java.util.function.Supplier;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;

/**
 * A plain, immutable {@link PlatformDestinationTree} node built from ready-made child maps.
 * Children stay behind {@link Supplier}s so a provider can defer materializing large sets (every
 * location, every town) until a node is actually visited during command traversal or
 * tab-completion.
 *
 * @param <W> the platform world type
 * @param <V> the platform vector type
 */
public final class SimplePlatformDestinationTree<W, V> implements PlatformDestinationTree<W, V> {

  private final boolean strict;
  private final Map<String, Supplier<? extends PlatformDestinationTree<W, V>>> subTrees;
  private final Map<String, Supplier<MinecraftDestination<W, V>>> destinations;

  /**
   * Creates a node.
   *
   * @param strict whether this level may never be omitted in commands
   * @param subTrees the child sub-trees, keyed by key
   * @param destinations the leaf destinations, keyed by key
   */
  public SimplePlatformDestinationTree(
      boolean strict,
      Map<String, ? extends Supplier<? extends PlatformDestinationTree<W, V>>> subTrees,
      Map<String, ? extends Supplier<MinecraftDestination<W, V>>> destinations) {
    this.strict = strict;
    this.subTrees = Map.copyOf(subTrees);
    this.destinations = Map.copyOf(destinations);
  }

  @Override
  public boolean strict() {
    return strict;
  }

  @Override
  public Map<String, Supplier<? extends PlatformDestinationTree<W, V>>> subTrees() {
    return subTrees;
  }

  @Override
  public Map<String, Supplier<MinecraftDestination<W, V>>> destinations() {
    return destinations;
  }
}
