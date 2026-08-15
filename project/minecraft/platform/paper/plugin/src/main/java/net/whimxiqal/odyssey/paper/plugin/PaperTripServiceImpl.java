/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.UUID;
import java.util.function.Supplier;
import net.whimxiqal.odyssey.Position;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.SearchHandle;
import net.whimxiqal.odyssey.api.SearchSettings;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.minecraft.MinecraftWorld;
import net.whimxiqal.odyssey.minecraft.api.MinecraftSearchSettings;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.PaperNavigationServiceImpl;
import net.whimxiqal.odyssey.paper.plugin.api.NavigatorFactory;
import net.whimxiqal.odyssey.paper.plugin.api.TrailNavigatorSettings;
import net.whimxiqal.odyssey.paper.plugin.api.TripService;
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import net.whimxiqal.odyssey.plugin.search.SearchGate;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.AbstractTripService;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * The Paper binding of {@link AbstractTripService}: the shared "search then guide" flow with
 * Paper's native player/location/scheduler operations filled in. The navigator is resolved from
 * Odyssey's {@link PaperIntegrationRegistry} (falling back to the default trail) and built on the
 * destination's region thread.
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
