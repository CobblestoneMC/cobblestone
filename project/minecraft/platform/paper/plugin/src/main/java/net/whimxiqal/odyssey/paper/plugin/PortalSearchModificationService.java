/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.whimxiqal.odyssey.minecraft.api.MinecraftStepPayload;
import net.whimxiqal.odyssey.paper.api.BoxWorldRegion;
import net.whimxiqal.odyssey.paper.api.SearchModificationService;
import net.whimxiqal.odyssey.paper.api.Transition;
import net.whimxiqal.odyssey.plugin.data.GatewayDao;
import net.whimxiqal.odyssey.plugin.data.GatewayTransition;
import net.whimxiqal.odyssey.plugin.data.PortalLink;
import net.whimxiqal.odyssey.plugin.data.PortalLinkDao;
import net.whimxiqal.odyssey.plugin.data.PortalRegion;
import net.whimxiqal.odyssey.plugin.data.PortalTransition;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The internal {@link SearchModificationService} that surfaces Odyssey's discovered portal links to
 * searches. Nether links are one transition per destination sub-region (entering that sub-region
 * arrives at the destination portal's centre); end links and end gateways are region → point.
 * Worlds unloaded since discovery are skipped until they return.
 */
public final class PortalSearchModificationService implements SearchModificationService {

  private static final MinecraftStepPayload PORTAL_PAYLOAD = MinecraftStepPayload.portal();

  private final PortalTransitionDao endPortals;
  private final PortalLinkDao netherLinks;
  private final GatewayDao gateways;

  /**
   * Creates the provider.
   *
   * @param endPortals the end-portal (region → point) DAO
   * @param netherLinks the nether-portal partition DAO
   * @param gateways the end-gateway (block → point) DAO
   */
  public PortalSearchModificationService(
      PortalTransitionDao endPortals, PortalLinkDao netherLinks, GatewayDao gateways) {
    this.endPortals = endPortals;
    this.netherLinks = netherLinks;
    this.gateways = gateways;
  }

  @Override
  public CompletableFuture<List<Transition>> computeTransitions(Player player) {
    List<Transition> result = new ArrayList<>();

    for (PortalLink link : netherLinks.all()) {
      World fromWorld = worldOf(link.source().world());
      World toWorld = worldOf(link.dest().world());
      if (fromWorld == null || toWorld == null) {
        continue; // a world unloaded since discovery; skip until it is back
      }
      PortalRegion sub = link.subRegion();
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              new Location(fromWorld, sub.minX(), sub.minY(), sub.minZ()),
              new Location(fromWorld, sub.maxX(), sub.maxY(), sub.maxZ()));
      PortalRegion dest = link.dest();
      Location destination = new Location(toWorld, dest.centerX(), dest.groundY(), dest.centerZ());
      result.add(Transition.of(origin, destination, link.cost(), PORTAL_PAYLOAD));
    }

    for (PortalTransition portal : endPortals.all()) {
      World fromWorld = worldOf(portal.fromWorld());
      World toWorld = worldOf(portal.toWorld());
      if (fromWorld == null || toWorld == null) {
        continue;
      }
      BoxWorldRegion origin =
          BoxWorldRegion.of(
              new Location(fromWorld, portal.minX(), portal.minY(), portal.minZ()),
              new Location(fromWorld, portal.maxX(), portal.maxY(), portal.maxZ()));
      Location destination = new Location(toWorld, portal.toX(), portal.toY(), portal.toZ());
      result.add(Transition.of(origin, destination, portal.cost(), PORTAL_PAYLOAD));
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

  private static World worldOf(String key) {
    NamespacedKey namespacedKey = NamespacedKey.fromString(key);
    return namespacedKey == null ? null : Bukkit.getWorld(namespacedKey);
  }
}
