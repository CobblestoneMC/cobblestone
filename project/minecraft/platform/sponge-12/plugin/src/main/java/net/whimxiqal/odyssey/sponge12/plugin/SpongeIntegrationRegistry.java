/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.whimxiqal.odyssey.minecraft.registry.OwnedRegistry;
import net.whimxiqal.odyssey.sponge12.plugin.api.DestinationService;
import net.whimxiqal.odyssey.sponge12.plugin.api.IntegrationRegistrar;
import net.whimxiqal.odyssey.sponge12.plugin.api.NavigatorFactory;
import org.spongepowered.plugin.PluginContainer;

/**
 * Odyssey's own store of the destinations and navigators integrations register (via {@link
 * IntegrationRegistrar}). The {@code /navigate} command reads the destination providers; the trip
 * service resolves a navigator by id. Everything an owner registered is {@link #purge purged} when
 * it stops.
 */
public final class SpongeIntegrationRegistry implements IntegrationRegistrar {

  private record NavigatorEntry(String id, NavigatorFactory factory) {}

  private final OwnedRegistry<DestinationService> destinations = new OwnedRegistry<>();
  private final OwnedRegistry<NavigatorEntry> navigators = new OwnedRegistry<>();

  @Override
  public void registerDestinations(PluginContainer owner, DestinationService provider) {
    destinations.register(owner.metadata().id(), provider);
  }

  @Override
  public void registerNavigator(PluginContainer owner, String id, NavigatorFactory factory) {
    navigators.register(owner.metadata().id(), new NavigatorEntry(id, factory));
  }

  /** The registered destination providers, in registration order. */
  Map<String, DestinationService> destinationProviders() {
    return destinations.map();
  }

  /**
   * The factory registered under a navigator id, or {@code null} if none. First registered wins.
   */
  NavigatorFactory navigator(String id) {
    for (NavigatorEntry entry : navigators.map().values()) {
      if (entry.id().equals(id)) {
        return entry.factory();
      }
    }
    return null;
  }

  /** The ids of all registered navigators, in registration order. */
  List<String> navigatorIds() {
    List<String> ids = new ArrayList<>();
    for (NavigatorEntry entry : navigators.map().values()) {
      ids.add(entry.id());
    }
    return ids;
  }

  /**
   * Drops everything a departing owner registered (called when that plugin stops).
   *
   * @param owner the departing owner's id
   */
  void purge(String owner) {
    destinations.purge(owner);
    navigators.purge(owner);
  }
}
