/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.sponge12.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.plugin.data.EndReturnPortal;
import org.cobblestonemc.plugin.data.EndReturnPortalDao;
import org.cobblestonemc.plugin.data.GatewayDao;
import org.cobblestonemc.plugin.data.GatewayTransition;
import org.cobblestonemc.plugin.data.PortalRegion;
import org.cobblestonemc.plugin.data.PortalTransition;
import org.cobblestonemc.plugin.data.PortalTransitionDao;
import org.cobblestonemc.sponge12.api.BoxWorldRegion;
import org.cobblestonemc.sponge12.api.SearchModificationService;
import org.cobblestonemc.sponge12.api.Transition;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.world.server.ServerLocation;
import org.spongepowered.api.world.server.ServerWorld;
import org.spongepowered.math.vector.Vector3i;

/**
 * Surfaces Cobblestone's discovered portal links to searches. Nether portals and the
 * overworld&nbsp;&rarr;&nbsp;End portal are region&nbsp;&rarr;&nbsp;point; End&nbsp;&rarr;&nbsp;
 * overworld portals resolve their destination per-search (v1: the overworld spawn); end gateways
 * are block&nbsp;&rarr;&nbsp;point. Worlds unloaded since discovery are skipped until they return.
 */
public final class SpongePortalSearchModificationService implements SearchModificationService {

  private static final MinecraftStepPayload PORTAL_PAYLOAD = MinecraftStepPayload.portal();

  private final PortalTransitionDao portals;
  private final EndReturnPortalDao endReturns;
  private final GatewayDao gateways;

  /**
   * Creates the provider.
   *
   * @param portals the region &rarr; point portal DAO (nether + overworld &rarr; End)
   * @param endReturns the End &rarr; overworld portal DAO
   * @param gateways the end-gateway (block &rarr; point) DAO
   */
  public SpongePortalSearchModificationService(
      PortalTransitionDao portals, EndReturnPortalDao endReturns, GatewayDao gateways) {
    this.portals = portals;
    this.endReturns = endReturns;
    this.gateways = gateways;
  }

  @Override
  public CompletableFuture<List<Transition>> computeTransitions(ServerPlayer player) {
    List<Transition> result = new ArrayList<>();

    for (PortalTransition portal : portals.all()) {
      ServerWorld fromWorld = worldOf(portal.fromWorld());
      ServerWorld toWorld = worldOf(portal.toWorld());
      if (fromWorld == null || toWorld == null) {
        continue; // a world unloaded since discovery; skip until it is back
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              ServerLocation.of(fromWorld, portal.minX(), portal.minY(), portal.minZ()),
              ServerLocation.of(fromWorld, portal.maxX(), portal.maxY(), portal.maxZ()));
      ServerLocation destination =
          ServerLocation.of(toWorld, portal.toX(), portal.toY(), portal.toZ());
      result.add(Transition.of(origin, destination, portal.cost(), PORTAL_PAYLOAD));
    }

    ServerLocation respawn = respawnLocationOf(player);
    for (EndReturnPortal portal : endReturns.all()) {
      PortalRegion region = portal.region();
      ServerWorld fromWorld = worldOf(region.world());
      if (fromWorld == null || respawn == null) {
        continue;
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              ServerLocation.of(fromWorld, region.minX(), region.minY(), region.minZ()),
              ServerLocation.of(fromWorld, region.maxX(), region.maxY(), region.maxZ()));
      result.add(Transition.of(origin, respawn, portal.cost(), PORTAL_PAYLOAD));
    }

    for (GatewayTransition gateway : gateways.all()) {
      ServerWorld fromWorld = worldOf(gateway.world());
      ServerWorld toWorld = worldOf(gateway.toWorld());
      if (fromWorld == null || toWorld == null) {
        continue;
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              ServerLocation.of(fromWorld, gateway.x(), gateway.y(), gateway.z()),
              ServerLocation.of(fromWorld, gateway.x(), gateway.y(), gateway.z()));
      ServerLocation destination =
          ServerLocation.of(toWorld, gateway.toX(), gateway.toY(), gateway.toZ());
      result.add(Transition.of(origin, destination, gateway.cost(), PORTAL_PAYLOAD));
    }

    return CompletableFuture.completedFuture(result);
  }

  /**
   * The destination for an End-return portal. v1: the overworld spawn (per-player bed/anchor
   * respawn via {@code Keys.RESPAWN_LOCATIONS} is a refinement). TODO(sponge)
   */
  private static ServerLocation respawnLocationOf(ServerPlayer player) {
    Optional<ServerWorld> overworld =
        Sponge.server().worldManager().world(ResourceKey.minecraft("overworld"));
    if (overworld.isEmpty()) {
      return null;
    }
    ServerWorld world = overworld.get();
    Vector3i spawn = world.properties().spawnPosition();
    return ServerLocation.of(world, spawn.x(), spawn.y(), spawn.z());
  }

  private static ServerWorld worldOf(String key) {
    return Sponge.server().worldManager().world(ResourceKey.resolve(key)).orElse(null);
  }
}
