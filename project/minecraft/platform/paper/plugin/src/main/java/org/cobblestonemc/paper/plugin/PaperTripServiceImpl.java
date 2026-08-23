/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.cobblestonemc.Position;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.paper.PaperNavigationServiceImpl;
import org.cobblestonemc.paper.plugin.api.NavigatorFactory;
import org.cobblestonemc.paper.plugin.api.TrailNavigatorSettings;
import org.cobblestonemc.paper.plugin.api.TripService;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.search.SearchGate;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.AbstractTripService;
import org.cobblestonemc.plugin.trip.TripManager;

/**
 * The Paper binding of {@link AbstractTripService}: the shared "search then guide" flow with
 * Paper's native player/location/scheduler operations filled in. The navigator is resolved from
 * Cobblestone's {@link PaperIntegrationRegistry} (falling back to the default trail) and built on
 * the destination's region thread.
 */
public final class PaperTripServiceImpl
    extends AbstractTripService<Player, Location, Entity, PaperTripAgent> implements TripService {

  private final PaperNavigationServiceImpl platformApi;
  private final PaperIntegrationRegistry integrations;

  PaperTripServiceImpl(
      PaperNavigationServiceImpl platformApi,
      PaperIntegrationRegistry integrations,
      TripManager<Entity, PaperTripAgent, Location> trips,
      SearchRegistry<Location> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      long liveIntervalMillis) {
    super(trips, searches, gate, searchSettings, liveIntervalMillis);
    this.platformApi = platformApi;
    this.integrations = integrations;
  }

  @Override
  protected UUID uuid(Player player) {
    return player.getUniqueId();
  }

  @Override
  protected boolean isOnline(Player player) {
    return player.isOnline();
  }

  @Override
  protected PaperTripAgent agent(Player player) {
    return new PaperTripAgent(player);
  }

  @Override
  protected SearchHandle<Location, MinecraftStepPayload> navigate(
      Player player, Location destination, MinecraftSearchSettings settings) {
    return platformApi.navigatePlayer(player, destination, settings);
  }

  @Override
  protected Position<MinecraftWorld> position(Location location) {
    return location.getWorld() == null ? null : platformApi.position(location);
  }

  @Override
  protected MinecraftScheduler<Entity> scheduler() {
    return platformApi.scheduler();
  }

  @Override
  protected Navigator<Location> createNavigator(
      Player player, Path<Location, MinecraftStepPayload> path, NavigatorSettings settings) {
    NavigatorFactory factory =
        navigatorFactory(settings.navigatorId().orElse(TrailNavigatorSettings.NAVIGATOR_ID));
    return factory.create(player, path, settings);
  }

  @Override
  protected String describe(Location location) {
    return location.getWorld().getKey().asString()
        + " "
        + location.getBlockX()
        + ","
        + location.getBlockY()
        + ","
        + location.getBlockZ();
  }

  private NavigatorFactory navigatorFactory(String id) {
    NavigatorFactory factory = integrations.navigator(id);
    return factory != null ? factory : integrations.navigator(TrailNavigatorSettings.NAVIGATOR_ID);
  }
}
