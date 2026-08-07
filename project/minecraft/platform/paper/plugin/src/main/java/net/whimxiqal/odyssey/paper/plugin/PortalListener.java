/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.paper.plugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.plugin.data.PortalTransition;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;

/**
 * Discovers vanilla portal links empirically: no API reveals where a portal leads, so when a player
 * teleports through one this captures the entry portal plane (as a bounding box) and the arrival
 * point, and persists a one-way {@link PortalTransition}. The reverse direction is only learned when
 * a player travels back. Persisting is idempotent, so re-walking a known portal is a no-op.
 */
final class PortalListener implements Listener {

  private static final Set<Material> PORTAL_BLOCKS =
      EnumSet.of(Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY);
  private static final int SCAN_HORIZONTAL = 3;
  private static final int SCAN_DOWN = 2;
  private static final int SCAN_UP = 3;

  private final PortalTransitionDao portals;
  private final MinecraftScheduler scheduler;
  private final DoubleSupplier cost;
  private final BooleanSupplier enabled;

  PortalListener(
      PortalTransitionDao portals, MinecraftScheduler scheduler,
      DoubleSupplier cost, BooleanSupplier enabled) {
    this.portals = portals;
    this.scheduler = scheduler;
    this.cost = cost;
    this.enabled = enabled;
  }

  @EventHandler
  void onPortal(PlayerPortalEvent event) {
    if (!enabled.getAsBoolean()) {
      return;
    }
    Location from = event.getFrom();
    Location to = event.getTo();
    if (to == null || from.getWorld() == null || to.getWorld() == null) {
      return;
    }
    int[] box = scanPortalBox(from);
    PortalTransition transition = new PortalTransition(
        from.getWorld().getKey().asString(),
        box[0], box[1], box[2], box[3], box[4], box[5],
        to.getWorld().getKey().asString(), to.getBlockX(), to.getBlockY(), to.getBlockZ(),
        cost.getAsDouble());
    // Persist off the server thread; the read of world blocks above already happened on it.
    scheduler.runAsync(() -> portals.add(transition));
  }

  /**
   * Returns {@code {minX, minY, minZ, maxX, maxY, maxZ}} of the portal blocks around {@code from},
   * or a single cell at {@code from} if none are found (e.g. a very large custom portal outside the
   * scan window, or an end gateway).
   */
  private static int[] scanPortalBox(Location from) {
    World world = from.getWorld();
    int fx = from.getBlockX();
    int fy = from.getBlockY();
    int fz = from.getBlockZ();
    boolean found = false;
    int minX = fx;
    int minY = fy;
    int minZ = fz;
    int maxX = fx;
    int maxY = fy;
    int maxZ = fz;
    for (int x = fx - SCAN_HORIZONTAL; x <= fx + SCAN_HORIZONTAL; x++) {
      for (int y = fy - SCAN_DOWN; y <= fy + SCAN_UP; y++) {
        for (int z = fz - SCAN_HORIZONTAL; z <= fz + SCAN_HORIZONTAL; z++) {
          if (!PORTAL_BLOCKS.contains(world.getBlockAt(x, y, z).getType())) {
            continue;
          }
          if (!found) {
            minX = maxX = x;
            minY = maxY = y;
            minZ = maxZ = z;
            found = true;
          } else {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
          }
        }
      }
    }
    return new int[] {minX, minY, minZ, maxX, maxY, maxZ};
  }
}
