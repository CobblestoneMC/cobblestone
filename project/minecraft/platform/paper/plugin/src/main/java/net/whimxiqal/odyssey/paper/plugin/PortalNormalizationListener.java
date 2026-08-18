/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.function.BooleanSupplier;
import net.whimxiqal.odyssey.plugin.data.PortalRegion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Optional determinism for nether portal linking, so Odyssey's block-granular routing is exact.
 * Both halves are config-gated and apply only to nether travel (the End is a fixed destination).
 *
 * <ul>
 *   <li><b>Entry</b> ({@link PlayerPortalEvent}, default off): the event's {@code to} is the portal
 *       <i>search origin</i>, so overriding it with the source block's scaled centre makes which
 *       destination portal you reach deterministic per block. This changes which portal you link
 *       to, hence off by default.
 *   <li><b>Exit</b> (the resolved {@link PlayerTeleportEvent}, default on): snaps the arrival to
 *       the destination portal's centre at ground level — only where within the same portal you
 *       land, so on by default. Runs at {@link EventPriority#HIGH} so the discovery listener at
 *       MONITOR sees the centred destination.
 * </ul>
 */
final class PortalNormalizationListener implements Listener {

  private final BooleanSupplier normalizeEntry;
  private final BooleanSupplier normalizeExit;

  PortalNormalizationListener(BooleanSupplier normalizeEntry, BooleanSupplier normalizeExit) {
    this.normalizeEntry = normalizeEntry;
    this.normalizeExit = normalizeExit;
  }

  @EventHandler(ignoreCancelled = true)
  public void onPortalEntry(PlayerPortalEvent event) {
    if (!normalizeEntry.getAsBoolean() || event.getCause() != TeleportCause.NETHER_PORTAL) {
      return;
    }
    Location from = event.getFrom();
    Location to = event.getTo();
    if (from.getWorld() == null || to == null || to.getWorld() == null) {
      return;
    }
    double factor = from.getWorld().getCoordinateScale() / to.getWorld().getCoordinateScale();
    double x = (from.getBlockX() + 0.5) * factor;
    double z = (from.getBlockZ() + 0.5) * factor;
    event.setTo(new Location(to.getWorld(), x, to.getY(), z, to.getYaw(), to.getPitch()));
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPortalExit(PlayerTeleportEvent event) {
    if (event instanceof PlayerPortalEvent) {
      return; // the search-origin event, handled by onPortalEntry — never the resolved arrival
    }
    if (!normalizeExit.getAsBoolean() || event.getCause() != TeleportCause.NETHER_PORTAL) {
      return;
    }
    Location to = event.getTo();
    if (to == null || to.getWorld() == null) {
      return;
    }
    PortalRegion portal = PaperPortals.scanPortal(to, Material.NETHER_PORTAL);
    event.setTo(
        new Location(
            to.getWorld(),
            portal.centerX(),
            portal.groundY(),
            portal.centerZ(),
            to.getYaw(),
            to.getPitch()));
  }
}
