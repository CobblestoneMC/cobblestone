/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.cobblestonemc.minecraft.registry.OwnedRegistry;
import org.cobblestonemc.sponge12.plugin.api.DestinationService;
import org.cobblestonemc.sponge12.plugin.api.IntegrationRegistrar;
import org.cobblestonemc.sponge12.plugin.api.NavigatorFactory;
import org.spongepowered.plugin.PluginContainer;

/**
 * Cobblestone's own store of the destinations and navigators integrations register (via {@link
 * IntegrationRegistrar}). The {@code /navigate} command reads the destination providers; the trip
 * service resolves a navigator by id. Everything an owner registered is {@link #purge purged} when
 * it stops.
 */
public final class SpongeIntegrationRegistry implements IntegrationRegistrar {

  private record NavigatorEntry(String owner, NavigatorFactory factory) {}

  private final OwnedRegistry<DestinationService> destinations = new OwnedRegistry<>();

  /**
   * Navigators are keyed by their own id, not by owner: a plugin may offer several, and {@code
   * /navigate -navigator <id>} looks one up by id. First registration of an id wins; who registered
   * it is remembered only so {@link #purge} can find it again.
   */
  private final ConcurrentSkipListMap<String, NavigatorEntry> navigators =
      new ConcurrentSkipListMap<>();

  @Override
  public void registerDestinations(PluginContainer owner, DestinationService provider) {
    destinations.register(owner.metadata().id(), provider);
  }

  @Override
  public void registerNavigator(PluginContainer owner, String id, NavigatorFactory factory) {
    navigators.putIfAbsent(id, new NavigatorEntry(owner.metadata().id(), factory));
  }

  /**
   * The registered destination providers, keyed by the plugin that registered each one. The key is
   * player-visible — it roots that plugin's {@code /navigate} branch — but Sponge already requires
   * plugin ids to be lower-case, so unlike Paper there is nothing to fold here.
   */
  Map<String, DestinationService> destinationProviders() {
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
   * Drops everything a departing owner registered (called when that plugin stops).
   *
   * @param owner the departing owner's id
   */
  void purge(String owner) {
    destinations.purge(owner);
    navigators.values().removeIf(entry -> entry.owner().equals(owner));
  }
}
