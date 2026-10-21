/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge.plugin;

import java.util.UUID;
import java.util.function.Supplier;
import org.cobblestonemc.Position;
import org.cobblestonemc.api.Path;
import org.cobblestonemc.api.SearchHandle;
import org.cobblestonemc.api.SearchSettings;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.cobblestonemc.minecraft.api.MinecraftSearchSettings;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.minecraft.regions.RegionStores;
import org.cobblestonemc.plugin.api.Navigator;
import org.cobblestonemc.plugin.api.NavigatorSettings;
import org.cobblestonemc.plugin.search.SearchGate;
import org.cobblestonemc.plugin.search.SearchRegistry;
import org.cobblestonemc.plugin.trip.AbstractTripService;
import org.cobblestonemc.plugin.trip.TripManager;
import org.cobblestonemc.sponge.SpongeNavigationServiceImpl;
import org.cobblestonemc.sponge.plugin.api.NavigatorFactory;
import org.cobblestonemc.sponge.plugin.api.TrailNavigatorSettings;
import org.cobblestonemc.sponge.plugin.api.TripService;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * The Sponge binding of {@link AbstractTripService}: the shared "search then guide" flow with
 * Sponge's native player/location/scheduler operations filled in. The navigator is resolved from
 * Cobblestone's {@link SpongeIntegrationRegistry} (falling back to the default trail).
 */
public final class SpongeTripServiceImpl
    extends AbstractTripService<ServerPlayer, ServerLocation, Entity, SpongeTripAgent>
    implements TripService {

  private final SpongeNavigationServiceImpl navigationService;
  private final SpongeIntegrationRegistry integrations;
  private final RegionStores regionStores;

  SpongeTripServiceImpl(
      SpongeNavigationServiceImpl navigationService,
      SpongeIntegrationRegistry integrations,
      TripManager<Entity, SpongeTripAgent, ServerLocation> trips,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      long liveIntervalMillis,
      RegionStores regionStores) {
    super(trips, searches, gate, searchSettings, liveIntervalMillis);
    this.navigationService = navigationService;
    this.integrations = integrations;
    this.regionStores = regionStores;
  }

  @Override
  protected UUID uuid(ServerPlayer player) {
    return player.uniqueId();
  }

  @Override
  protected boolean isOnline(ServerPlayer player) {
    return player.isOnline();
  }

  @Override
  protected SpongeTripAgent agent(ServerPlayer player) {
    return new SpongeTripAgent(player);
  }

  @Override
  protected SearchHandle<ServerLocation, MinecraftStepPayload> navigate(
      ServerPlayer player, ServerLocation destination, MinecraftSearchSettings settings) {
    // A trip's searches start where the player is, so their surroundings stay built for the
    // searches that re-plan around them; released when the player's last trip ends.
    Position<MinecraftWorld> at = navigationService.position(player.serverLocation());
    regionStores.pin(uuid(player), at.domain(), at.cell().x() >> 4, at.cell().z() >> 4);
    return navigationService.navigatePlayer(player, destination, settings);
  }

  @Override
  protected Position<MinecraftWorld> position(ServerLocation location) {
    return navigationService.position(location);
  }

  @Override
  protected MinecraftScheduler<Entity> scheduler() {
    return navigationService.scheduler();
  }

  @Override
  protected Navigator<ServerLocation> createNavigator(
      ServerPlayer player,
      Path<ServerLocation, MinecraftStepPayload> path,
      NavigatorSettings settings) {
    NavigatorFactory factory =
        navigatorFactory(settings.navigatorId().orElse(TrailNavigatorSettings.NAVIGATOR_ID));
    return factory.create(player, path, settings);
  }

  private NavigatorFactory navigatorFactory(String id) {
    NavigatorFactory factory = integrations.navigator(id);
    return factory != null ? factory : integrations.navigator(TrailNavigatorSettings.NAVIGATOR_ID);
  }
}
