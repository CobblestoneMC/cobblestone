/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cobblestonemc.minecraft.registry.OwnedRegistry;
import org.cobblestonemc.paper.PaperRegions;
import org.cobblestonemc.paper.plugin.api.DestinationService;
import org.cobblestonemc.paper.plugin.api.IntegrationRegistrar;
import org.cobblestonemc.paper.plugin.api.NavigatorFactory;
import org.cobblestonemc.plugin.api.DestinationNode;
import org.cobblestonemc.plugin.api.Display;
import org.cobblestonemc.plugin.destination.DestinationProvider;
import org.cobblestonemc.plugin.destination.NavigationTarget;
import org.cobblestonemc.plugin.destination.PlatformTrees;

/**
 * Cobblestone's own store of the destinations and navigators integrations register (via {@link
 * IntegrationRegistrar}). The {@code /navigate} command reads the destination providers; the trip
 * service resolves a navigator by id. Everything an owner registered is {@link #purge purged} when
 * it disables.
 */
public final class PaperIntegrationRegistry implements IntegrationRegistrar {

  private record NavigatorEntry(String owner, NavigatorFactory factory) {}

  private final OwnedRegistry<DestinationProvider<Player>> destinations = new OwnedRegistry<>();
  private final PaperRegions regions;

  /**
   * Navigators are keyed by their own id, not by owner: a plugin may offer several, and {@code
   * /navigate -navigator <id>} looks one up by id. First registration of an id wins; who registered
   * it is remembered only so {@link #purge} can find it again.
   */
  private final ConcurrentSkipListMap<String, NavigatorEntry> navigators =
      new ConcurrentSkipListMap<>();

  /**
   * Creates an empty registry.
   *
   * @param regions converts the regions in integrations' trees into the search's
   */
  PaperIntegrationRegistry(PaperRegions regions) {
    this.regions = regions;
  }

  /**
   * Registers an integration's destinations, which join the {@code /navigate} tree {@linkplain
   * PlatformTrees#adapt in Cobblestone's own terms}.
   */
  @Override
  public void registerDestinations(Plugin owner, DestinationService provider) {
    registerProvider(
        owner,
        new DestinationProvider<>() {
          @Override
          public DestinationNode<NavigationTarget> provide(Player player) {
            var tree = provider.provide(player);
            return tree == null ? null : PlatformTrees.adapt(tree, regions::of);
          }

          @Override
          public Display display(Player player) {
            return provider.display(player);
          }
        });
  }

  /**
   * Registers destinations already written in Cobblestone's own terms — its built-in ones.
   *
   * @param owner the registering plugin, whose key roots them, as for an integration
   * @param provider the destinations
   */
  void registerProvider(Plugin owner, DestinationProvider<Player> provider) {
    destinations.register(key(owner), provider);
  }

  @Override
  public void registerNavigator(Plugin owner, String id, NavigatorFactory factory) {
    navigators.putIfAbsent(id, new NavigatorEntry(key(owner), factory));
  }

  /**
   * An owner's registry key: its plugin name, lower-cased. The key is player-visible — it is the
   * root of that plugin's {@code /navigate} branch and so part of every {@code
   * cobblestone.navigate.<address>} node — and permission plugins are inconsistent about case, so
   * it is folded once here rather than at each use.
   */
  private static String key(Plugin owner) {
    return owner.getName().toLowerCase(Locale.ROOT);
  }

  /** The registered destination providers, keyed by the plugin that registered each one. */
  Map<String, DestinationProvider<Player>> destinationProviders() {
    return destinations.map();
  }

  /**
   * The factory registered under a navigator id, or {@code null} if none. First registered wins.
   */
  NavigatorFactory navigator(String id) {
    NavigatorEntry entry = navigators.get(id);
    return entry == null ? null : entry.factory();
  }

  /** The ids of all registered navigators, alphabetically. */
  List<String> navigatorIds() {
    return List.copyOf(navigators.keySet());
  }

  /**
   * Drops everything a departing owner registered (called when that plugin disables).
   *
   * @param owner the departing owner's plugin name (folded to the registry key here)
   */
  void purge(String owner) {
    String key = owner.toLowerCase(Locale.ROOT);
    destinations.purge(key);
    navigators.values().removeIf(entry -> entry.owner().equals(key));
  }
}
