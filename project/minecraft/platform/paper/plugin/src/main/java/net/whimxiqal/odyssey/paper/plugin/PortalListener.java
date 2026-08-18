/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.plugin.data.GatewayDao;
import net.whimxiqal.odyssey.plugin.data.GatewayTransition;
import net.whimxiqal.odyssey.plugin.data.NetherPortalPartitioner;
import net.whimxiqal.odyssey.plugin.data.PortalCacheDao;
import net.whimxiqal.odyssey.plugin.data.PortalLink;
import net.whimxiqal.odyssey.plugin.data.PortalLinkDao;
import net.whimxiqal.odyssey.plugin.data.PortalRegion;
import net.whimxiqal.odyssey.plugin.data.PortalTransition;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Discovers vanilla portal links empirically (no API reveals where a portal leads), reading the
 * <i>resolved</i> {@link PlayerTeleportEvent} at MONITOR (so any normalization has already run).
 *
 * <ul>
 *   <li><b>Nether</b> portals link ambiguously — which destination portal you reach depends on
 *       which block you enter. So a nether teleport upserts both portals into the cache and
 *       recomputes the source portal's destination <b>partition</b> ({@link PortalLink}s), which
 *       <i>updates</i> rather than appending duplicate rows.
 *   <li><b>End</b> portals are unambiguous (one frame → the fixed End platform), so they stay a
 *       simple region → point {@link PortalTransition}.
 *   <li><b>End gateways</b> link one block → one exit point, so a teleport caches the resolved exit
 *       keyed by the gateway block, updating it if the destination has since drifted.
 * </ul>
 *
 * <p>Block reads happen on the server thread (in the event); persistence and the partition math run
 * off-thread.
 */
final class PortalListener implements Listener {

  private final PortalTransitionDao endPortals;
  private final PortalCacheDao netherCache;
  private final PortalLinkDao netherLinks;
  private final GatewayDao gateways;
  private final MinecraftScheduler<?> scheduler;
  private final OdysseyLogger logger;
  private final DoubleSupplier cost;
  private final BooleanSupplier enabled;

  PortalListener(
      PortalTransitionDao endPortals,
      PortalCacheDao netherCache,
      PortalLinkDao netherLinks,
      GatewayDao gateways,
      MinecraftScheduler<?> scheduler,
      OdysseyLogger logger,
      DoubleSupplier cost,
      BooleanSupplier enabled) {
    this.endPortals = endPortals;
    this.netherCache = netherCache;
    this.netherLinks = netherLinks;
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

  /** Upserts both portals into the cache and replaces the source portal's destination partition. */
  private void recordNether(Location from, Location to) {
    PortalRegion source = PaperPortals.scanPortal(from, Material.NETHER_PORTAL);
    PortalRegion dest = PaperPortals.scanPortal(to, Material.NETHER_PORTAL);
    double factor = from.getWorld().getCoordinateScale() / to.getWorld().getCoordinateScale();
    double linkCost = cost.getAsDouble();
    logger.debug(
        "Discovered nether portal link {} -> {}", from.getWorld().getKey(), to.getWorld().getKey());
    scheduler.runAsync(
        () -> {
          netherCache.upsert(source);
          netherCache.upsert(dest);
          List<PortalRegion> candidates = netherCache.inWorld(dest.world());
          List<PortalLink> links =
              NetherPortalPartitioner.partition(source, candidates, factor, linkCost);
          netherLinks.replaceForSource(source, links);
        });
  }

  /** Records an unambiguous end-portal link as a region → point transition (idempotent). */
  private void recordEnd(Location from, Location to) {
    PortalRegion box = PaperPortals.scanPortal(from, Material.END_PORTAL);
    PortalTransition transition =
        new PortalTransition(
            box.world(),
            box.minX(),
            box.minY(),
            box.minZ(),
            box.maxX(),
            box.maxY(),
            box.maxZ(),
            to.getWorld().getKey().asString(),
            to.getBlockX(),
            to.getBlockY(),
            to.getBlockZ(),
            cost.getAsDouble());
    scheduler.runAsync(() -> endPortals.add(transition));
  }

  /**
   * Caches an end-gateway's exit, keyed by the gateway block. A gateway's exit <i>is</i> readable
   * from its block entity, but caching what a teleport actually resolved to avoids scanning every
   * gateway block at search time; a later teleport through the same block updates the exit if it
   * has since changed.
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
}
