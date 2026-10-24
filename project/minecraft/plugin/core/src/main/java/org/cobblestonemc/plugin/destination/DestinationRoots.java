/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.destination;

import java.util.Map;
import java.util.TreeMap;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.plugin.api.DestinationTree;

/** Gathers the roots of the destination tree a player navigates, one per registered provider. */
public final class DestinationRoots {

  private DestinationRoots() {}

  /**
   * The destination forest for a player: one root per provider, keyed by the plugin that registered
   * it. The owner is the root key rather than something the provider picks, so two plugins that
   * both offer a {@code warp} subtree stay tellable apart.
   *
   * <p>A provider that throws is logged and left out, so one broken integration costs the player
   * only its own destinations.
   *
   * @param providers the registered providers, keyed by owner
   * @param player the player
   * @param log where a failing provider is reported
   * @param <P> the platform player type
   * @return the roots, alphabetically
   */
  public static <P> Map<String, DestinationTree<NavigationTarget>> of(
      Map<String, ? extends DestinationProvider<P>> providers, P player, CobblestoneLogger log) {
    Map<String, DestinationTree<NavigationTarget>> roots = new TreeMap<>();
    providers.forEach(
        (owner, provider) -> {
          DestinationTree<NavigationTarget> root;
          try {
            root = provider.provide(player);
          } catch (RuntimeException e) {
            log.error("{} failed to provide its destinations", e, owner);
            return;
          }
          // An empty root would be offered as a token that leads nowhere — drop it instead.
          if (root != null && !root.entries().isEmpty()) {
            roots.put(owner, root);
          }
        });
    return roots;
  }
}
