/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.cobblestonemc.DomainRegion;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.WorldRegion;
import org.cobblestonemc.plugin.api.DestinationLeaf;
import org.cobblestonemc.plugin.api.DestinationOption;
import org.cobblestonemc.plugin.api.MinecraftDestination;
import org.cobblestonemc.plugin.api.OptionValues;
import org.cobblestonemc.plugin.api.PlatformDestinationTree;
import org.cobblestonemc.plugin.api.Validity;

/**
 * Brings an integration's {@link PlatformDestinationTree} into Cobblestone's own terms: a {@link
 * DestinationNode} of {@link NavigationTarget}s, with every region converted from the platform's
 * type to the search's. The view is as lazy as the tree it wraps — nothing is converted until a
 * level is visited or a leaf is built — and each search re-reads the integration's regions, so its
 * moving targets are followed as before.
 */
public final class PlatformTrees {

  private PlatformTrees() {}

  /**
   * Views an integration's tree as one of {@link NavigationTarget}s.
   *
   * @param tree the integration's tree
   * @param regions converts a platform region to the search's, or returns empty when it cannot be
   *     searched right now (its world is not loaded, say)
   * @param <W> the platform world type
   * @param <V> the platform vector type
   * @return the view
   */
  public static <W, V> DestinationNode<NavigationTarget> adapt(
      PlatformDestinationTree<W, V> tree,
      Function<WorldRegion<W, V>, Optional<DomainRegion<MinecraftWorld>>> regions) {
    Map<String, Supplier<? extends DestinationNode<NavigationTarget>>> subTrees =
        new LinkedHashMap<>();
    tree.subTrees().forEach((key, child) -> subTrees.put(key, () -> adapt(child.get(), regions)));
    Map<String, DestinationLeaf<NavigationTarget>> leaves = new LinkedHashMap<>();
    tree.destinations().forEach((key, leaf) -> leaves.put(key, new Leaf<>(leaf, regions)));
    return new DestinationNode.Simple<>(tree.strict(), subTrees, leaves);
  }

  /**
   * An integration's destination as a {@link NavigationTarget}.
   *
   * @param destination the integration's destination
   * @param regions converts its regions, as for {@link #adapt}
   * @param <W> the platform world type
   * @param <V> the platform vector type
   * @return the target
   */
  public static <W, V> NavigationTarget target(
      MinecraftDestination<W, V> destination,
      Function<WorldRegion<W, V>, Optional<DomainRegion<MinecraftWorld>>> regions) {
    return new NavigationTarget(
        () ->
            destination.destination().regions().stream()
                .map(regions)
                .flatMap(Optional::stream)
                .toList(),
        destination.displayName(),
        destination.isMobile(),
        destination.detail(),
        destination::exists);
  }

  /** An integration's leaf, described as it describes itself and built into a target. */
  private record Leaf<W, V>(
      DestinationLeaf<MinecraftDestination<W, V>> leaf,
      Function<WorldRegion<W, V>, Optional<DomainRegion<MinecraftWorld>>> regions)
      implements DestinationLeaf<NavigationTarget> {

    @Override
    public List<String> permissions() {
      return leaf.permissions();
    }

    @Override
    public List<DestinationOption<?>> options() {
      return leaf.options();
    }

    @Override
    public Validity<NavigationTarget> build(OptionValues values) {
      return leaf.build(values).map(destination -> target(destination, regions));
    }
  }
}
