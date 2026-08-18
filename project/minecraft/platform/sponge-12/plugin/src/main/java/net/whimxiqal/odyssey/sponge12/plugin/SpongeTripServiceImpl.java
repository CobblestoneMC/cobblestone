/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

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
import net.whimxiqal.odyssey.plugin.api.Navigator;
import net.whimxiqal.odyssey.plugin.api.NavigatorSettings;
import net.whimxiqal.odyssey.plugin.search.SearchGate;
import net.whimxiqal.odyssey.plugin.search.SearchRegistry;
import net.whimxiqal.odyssey.plugin.trip.AbstractTripService;
import net.whimxiqal.odyssey.plugin.trip.TripManager;
import net.whimxiqal.odyssey.sponge12.SpongeNavigationServiceImpl;
import net.whimxiqal.odyssey.sponge12.plugin.api.NavigatorFactory;
import net.whimxiqal.odyssey.sponge12.plugin.api.TrailNavigatorSettings;
import net.whimxiqal.odyssey.sponge12.plugin.api.TripService;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;

/**
 * The Sponge binding of {@link AbstractTripService}: the shared "search then guide" flow with
 * Sponge's native player/location/scheduler operations filled in. The navigator is resolved from
 * Odyssey's {@link SpongeIntegrationRegistry} (falling back to the default trail).
 */
public final class SpongeTripServiceImpl
    extends AbstractTripService<ServerPlayer, ServerLocation, Entity, SpongeTripAgent>
    implements TripService {

  private final SpongeNavigationServiceImpl navigationService;
  private final SpongeIntegrationRegistry integrations;

  SpongeTripServiceImpl(
      SpongeNavigationServiceImpl navigationService,
      SpongeIntegrationRegistry integrations,
      TripManager<Entity, SpongeTripAgent, ServerLocation> trips,
      SearchRegistry<ServerLocation> searches,
      SearchGate gate,
      Supplier<SearchSettings> searchSettings,
      long liveIntervalMillis) {
    super(trips, searches, gate, searchSettings, liveIntervalMillis);
    this.navigationService = navigationService;
    this.integrations = integrations;
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

  @Override
  protected String describe(ServerLocation location) {
    return location.world().key().asString()
        + " "
        + location.blockX()
        + ","
        + location.blockY()
        + ","
        + location.blockZ();
  }

  private NavigatorFactory navigatorFactory(String id) {
    NavigatorFactory factory = integrations.navigator(id);
    return factory != null ? factory : integrations.navigator(TrailNavigatorSettings.NAVIGATOR_ID);
  }
}
