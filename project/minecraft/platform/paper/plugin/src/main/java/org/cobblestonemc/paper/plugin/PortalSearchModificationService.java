/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.cobblestonemc.minecraft.api.MinecraftStepPayload;
import org.cobblestonemc.paper.api.BoxWorldRegion;
import org.cobblestonemc.paper.api.SearchModificationService;
import org.cobblestonemc.paper.api.Transition;
import org.cobblestonemc.plugin.data.EndReturnPortal;
import org.cobblestonemc.plugin.data.EndReturnPortalDao;
import org.cobblestonemc.plugin.data.GatewayDao;
import org.cobblestonemc.plugin.data.GatewayTransition;
import org.cobblestonemc.plugin.data.PortalRegion;
import org.cobblestonemc.plugin.data.PortalTransition;
import org.cobblestonemc.plugin.data.PortalTransitionDao;

/**
 * The internal {@link SearchModificationService} that surfaces Cobblestone's discovered portal
 * links to searches. Nether portals and the overworld&nbsp;&rarr;&nbsp;End portal are
 * region&nbsp;&rarr; point; End&nbsp;&rarr;&nbsp;overworld portals resolve their destination
 * per-player (the routed player's respawn location); end gateways are block&nbsp;&rarr;&nbsp;point.
 * Worlds unloaded since discovery are skipped until they return.
 */
public final class PortalSearchModificationService implements SearchModificationService {

  private static final MinecraftStepPayload PORTAL_PAYLOAD = MinecraftStepPayload.portal();

  private final PortalTransitionDao portals;
  private final EndReturnPortalDao endReturns;
  private final GatewayDao gateways;

  /**
   * Creates the provider.
   *
   * @param portals the region &rarr; point portal DAO (nether + overworld &rarr; End)
   * @param endReturns the End &rarr; overworld portal DAO (destination resolved per-player)
   * @param gateways the end-gateway (block &rarr; point) DAO
   */
  public PortalSearchModificationService(
      PortalTransitionDao portals, EndReturnPortalDao endReturns, GatewayDao gateways) {
    this.portals = portals;
    this.endReturns = endReturns;
    this.gateways = gateways;
  }

  @Override
  public CompletableFuture<List<Transition>> computeTransitions(Player player) {
    List<Transition> result = new ArrayList<>();

    for (PortalTransition portal : portals.all()) {
      World fromWorld = worldOf(portal.fromWorld());
      World toWorld = worldOf(portal.toWorld());
      if (fromWorld == null || toWorld == null) {
        continue; // a world unloaded since discovery; skip until it is back
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              new Location(fromWorld, portal.minX(), portal.minY(), portal.minZ()),
              new Location(fromWorld, portal.maxX(), portal.maxY(), portal.maxZ()));
      Location destination = new Location(toWorld, portal.toX(), portal.toY(), portal.toZ());
      result.add(Transition.of(origin, destination, portal.cost(), PORTAL_PAYLOAD));
    }

    Location respawn = respawnLocationOf(player);
    for (EndReturnPortal portal : endReturns.all()) {
      PortalRegion region = portal.region();
      World fromWorld = worldOf(region.world());
      if (fromWorld == null || respawn == null || respawn.getWorld() == null) {
        continue;
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              new Location(fromWorld, region.minX(), region.minY(), region.minZ()),
              new Location(fromWorld, region.maxX(), region.maxY(), region.maxZ()));
      result.add(Transition.of(origin, respawn, portal.cost(), PORTAL_PAYLOAD));
    }

    for (GatewayTransition gateway : gateways.all()) {
      World fromWorld = worldOf(gateway.world());
      World toWorld = worldOf(gateway.toWorld());
      if (fromWorld == null || toWorld == null) {
        continue;
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              new Location(fromWorld, gateway.x(), gateway.y(), gateway.z()),
              new Location(fromWorld, gateway.x(), gateway.y(), gateway.z()));
      Location destination = new Location(toWorld, gateway.toX(), gateway.toY(), gateway.toZ());
      result.add(Transition.of(origin, destination, gateway.cost(), PORTAL_PAYLOAD));
    }

    return CompletableFuture.completedFuture(result);
  }

  /** The player's respawn point (bed/anchor), else the main world's spawn. */
  private static Location respawnLocationOf(Player player) {
    Location respawn = player.getRespawnLocation();
    if (respawn != null) {
      return respawn;
    }
    List<World> worlds = Bukkit.getWorlds();
    return worlds.isEmpty() ? null : worlds.get(0).getSpawnLocation();
  }

  private static World worldOf(String key) {
    NamespacedKey namespacedKey = NamespacedKey.fromString(key);
    return namespacedKey == null ? null : Bukkit.getWorld(namespacedKey);
  }
}
