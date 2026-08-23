/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.cobblestonemc.CobblestoneLogger;
import org.cobblestonemc.minecraft.MinecraftScheduler;
import org.cobblestonemc.plugin.data.EndReturnPortal;
import org.cobblestonemc.plugin.data.EndReturnPortalDao;
import org.cobblestonemc.plugin.data.GatewayDao;
import org.cobblestonemc.plugin.data.GatewayTransition;
import org.cobblestonemc.plugin.data.PortalRegion;
import org.cobblestonemc.plugin.data.PortalTransition;
import org.cobblestonemc.plugin.data.PortalTransitionDao;

/**
 * Discovers vanilla portal links empirically (no API reveals where a portal leads), reading the
 * <i>resolved</i> {@link PlayerTeleportEvent} at MONITOR (so any normalization has already run).
 *
 * <ul>
 *   <li><b>Nether</b> and the <b>overworld&nbsp;&rarr;&nbsp;End</b> portal are region&nbsp;&rarr;
 *       point links, upserted by their source portal so re-walking a re-linked portal updates the
 *       arrival rather than adding a duplicate. Nether determinism (one source portal always
 *       reaches one destination) is provided by entry normalization; see {@link
 *       PortalNormalizationListener}.
 *   <li><b>End&nbsp;&rarr;&nbsp;overworld</b> (the End exit portal) teleports each player to their
 *       own respawn point, so only the portal region is cached; the destination is resolved
 *       per-player at search time.
 *   <li><b>End gateways</b> cache the resolved exit keyed by the gateway block, updating it if the
 *       destination has since drifted.
 * </ul>
 *
 * <p>Block reads happen on the server thread (in the event); persistence runs off-thread.
 */
final class PortalListener implements Listener {

  private final PortalTransitionDao portals;
  private final EndReturnPortalDao endReturns;
  private final GatewayDao gateways;
  private final MinecraftScheduler<?> scheduler;
  private final CobblestoneLogger logger;
  private final DoubleSupplier cost;
  private final BooleanSupplier enabled;

  PortalListener(
      PortalTransitionDao portals,
      EndReturnPortalDao endReturns,
      GatewayDao gateways,
      MinecraftScheduler<?> scheduler,
      CobblestoneLogger logger,
      DoubleSupplier cost,
      BooleanSupplier enabled) {
    this.portals = portals;
    this.endReturns = endReturns;
    this.gateways = gateways;
    this.scheduler = scheduler;
    this.logger = logger;
    this.cost = cost;
    this.enabled = enabled;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onTeleport(PlayerTeleportEvent event) {
    if (!enabled.getAsBoolean()) {
      return;
    }
    Location from = event.getFrom();
    Location to = event.getTo();
    if (from.getWorld() == null || to == null || to.getWorld() == null) {
      return; // arrival not resolved on this event
    }
    switch (event.getCause()) {
      case NETHER_PORTAL -> recordNether(from, to);
      case END_PORTAL -> recordEnd(from, to);
      case END_GATEWAY -> recordGateway(from, to);
      default -> {
        // not a learned portal teleport
      }
    }
  }

  /** Upserts a source portal &rarr; destination-portal-center link, keyed by the source portal. */
  private void recordNether(Location from, Location to) {
    PortalRegion source = PaperPortals.scanPortal(from, Material.NETHER_PORTAL);
    PortalRegion dest = PaperPortals.scanPortal(to, Material.NETHER_PORTAL);
    logger.debug(
        "Discovered nether portal link {} -> {}", from.getWorld().getKey(), to.getWorld().getKey());
    PortalTransition transition = transitionTo(source, dest.world(), centerPoint(dest));
    scheduler.runAsync(() -> portals.upsert(transition));
  }

  /**
   * Records an End portal. The overworld&nbsp;&rarr;&nbsp;End direction is an unambiguous region
   * &rarr; point link; the End&nbsp;&rarr;&nbsp;overworld direction (per-player respawn) caches
   * only the portal region.
   */
  private void recordEnd(Location from, Location to) {
    if (from.getWorld().getEnvironment() == World.Environment.THE_END) {
      PortalRegion region = PaperPortals.scanPortal(from, Material.END_PORTAL);
      EndReturnPortal portal = new EndReturnPortal(region, cost.getAsDouble());
      scheduler.runAsync(() -> endReturns.upsert(portal));
      return;
    }
    PortalRegion source = PaperPortals.scanPortal(from, Material.END_PORTAL);
    PortalTransition transition =
        transitionTo(
            source,
            to.getWorld().getKey().asString(),
            new int[] {to.getBlockX(), to.getBlockY(), to.getBlockZ()});
    scheduler.runAsync(() -> portals.upsert(transition));
  }

  /**
   * Caches an end-gateway's exit, keyed by the gateway block. Caching what a teleport actually
   * resolved to avoids scanning every gateway block at search time; a later teleport through the
   * same block updates the exit if it has since changed.
   */
  private void recordGateway(Location from, Location to) {
    PortalRegion box = PaperPortals.scanPortal(from, Material.END_GATEWAY);
    GatewayTransition gateway =
        new GatewayTransition(
            box.world(),
            box.minX(),
            box.minY(),
            box.minZ(),
            to.getWorld().getKey().asString(),
            to.getBlockX(),
            to.getBlockY(),
            to.getBlockZ(),
            cost.getAsDouble());
    scheduler.runAsync(() -> gateways.upsert(gateway));
  }

  /** Builds a region &rarr; point transition from a source region to an arrival block. */
  private PortalTransition transitionTo(PortalRegion source, String toWorld, int[] to) {
    return new PortalTransition(
        source.world(),
        source.minX(),
        source.minY(),
        source.minZ(),
        source.maxX(),
        source.maxY(),
        source.maxZ(),
        toWorld,
        to[0],
        to[1],
        to[2],
        cost.getAsDouble());
  }

  /** The destination portal's horizontal center at ground level, as a block coordinate. */
  private static int[] centerPoint(PortalRegion portal) {
    return new int[] {
      (int) Math.floor(portal.centerX()), portal.groundY(), (int) Math.floor(portal.centerZ())
    };
  }
}
