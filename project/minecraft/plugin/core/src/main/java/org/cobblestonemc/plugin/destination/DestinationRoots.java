/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.Map;
import java.util.TreeMap;
import org.cobblestonemc.plugin.api.DestinationTree;

/** Gathers the roots of the destination tree a player navigates, one per registered provider. */
public final class DestinationRoots {

  private DestinationRoots() {}

  /**
   * The destination forest for a player: one root per provider, keyed by the plugin that registered
   * it. The owner is the root key rather than something the provider picks, so two plugins that
   * both offer a {@code warp} subtree stay tellable apart.
   *
   * @param providers the registered providers, keyed by owner
   * @param player the player
   * @param <P> the platform player type
   * @return the roots, alphabetically
   */
  public static <P> Map<String, DestinationTree<NavigationTarget>> of(
      Map<String, ? extends DestinationProvider<P>> providers, P player) {
    Map<String, DestinationTree<NavigationTarget>> roots = new TreeMap<>();
    providers.forEach(
        (owner, provider) -> {
          DestinationTree<NavigationTarget> root = provider.provide(player);
          // An empty root would be offered as a token that leads nowhere — drop it instead.
          if (root != null && !root.entries().isEmpty()) {
            roots.put(owner, root);
          }
        });
    return roots;
  }
}
