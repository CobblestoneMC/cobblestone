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
import net.whimxiqal.odyssey.OdysseyLogger;
import net.whimxiqal.odyssey.minecraft.MinecraftScheduler;
import net.whimxiqal.odyssey.plugin.data.PortalTransition;
import net.whimxiqal.odyssey.plugin.data.PortalTransitionDao;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Discovers vanilla portal links empirically: no API reveals where a portal leads, so when a player
 * teleports through one this captures the entry portal (as a bounding box) and the arrival point, and
 * persists a one-way {@link PortalTransition}. The reverse direction is only learned when a player
 * travels back. Persisting is idempotent, so re-walking a known portal is a no-op.
 *
 * <p>Both {@link PlayerPortalEvent} and {@link PlayerTeleportEvent} are handled for coverage: the
 * former can arrive with an unresolved {@code getTo()} (skipped), the latter carries the real
 * arrival. (Some server forks currently fail to fire either for portals — an upstream issue.)
 */
final class PortalListener implements Listener {

  private static final Set<Material> PORTAL_BLOCKS =
      EnumSet.of(Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY);
  private static final Set<TeleportCause> PORTAL_CAUSES =
      EnumSet.of(TeleportCause.NETHER_PORTAL, TeleportCause.END_PORTAL, TeleportCause.END_GATEWAY);
  private static final int SEED_RADIUS = 2;   // where to look for the entry portal block near `from`
  private static final int MAX_EXPAND = 64;   // cap the outward scan (nether portals top out at ~23)

  private final PortalTransitionDao portals;
  private final MinecraftScheduler scheduler;
  private final OdysseyLogger logger;
  private final DoubleSupplier cost;
  private final BooleanSupplier enabled;

  PortalListener(
      PortalTransitionDao portals, MinecraftScheduler scheduler, OdysseyLogger logger,
      DoubleSupplier cost, BooleanSupplier enabled) {
    this.portals = portals;
    this.scheduler = scheduler;
    this.logger = logger;
    this.cost = cost;
    this.enabled = enabled;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPortal(PlayerPortalEvent event) {
    record(event.getCause(), event.getFrom(), event.getTo());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onTeleport(PlayerTeleportEvent event) {
    record(event.getCause(), event.getFrom(), event.getTo());
  }

  private void record(TeleportCause cause, Location from, Location to) {
    if (!enabled.getAsBoolean() || !PORTAL_CAUSES.contains(cause)) {
      return;
    }
    if (from.getWorld() == null || to == null || to.getWorld() == null) {
      return; // arrival not resolved on this event; another handler / direction will catch it
    }
    int[] box = scanPortalBox(from);
    PortalTransition transition = new PortalTransition(
        from.getWorld().getKey().asString(),
        box[0], box[1], box[2], box[3], box[4], box[5],
        to.getWorld().getKey().asString(), to.getBlockX(), to.getBlockY(), to.getBlockZ(),
        cost.getAsDouble());
    logger.debug("Discovered portal {} -> {}:{},{},{}", from.getWorld().getKey(),
        to.getWorld().getKey(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
    // Persist off the server thread; the block reads above already happened on it.
    scheduler.runAsync(() -> portals.add(transition));
  }

  /**
   * Returns {@code {minX, minY, minZ, maxX, maxY, maxZ}} of the entry portal: it seeds on the portal
   * block the player was in and expands outward in every axis while that <i>same</i> material
   * continues, so a large nether portal is captured in full. Falls back to a single cell at
   * {@code from} if no portal block is found nearby.
   */
  private static int[] scanPortalBox(Location from) {
    World world = from.getWorld();
    Block seed = findPortalBlock(world, from.getBlockX(), from.getBlockY(), from.getBlockZ());
    if (seed == null) {
      int x = from.getBlockX();
      int y = from.getBlockY();
      int z = from.getBlockZ();
      return new int[] {x, y, z, x, y, z};
    }
    Material material = seed.getType();
    int sx = seed.getX();
    int sy = seed.getY();
    int sz = seed.getZ();
    return new int[] {
        expand(world, material, sx, sy, sz, -1, 0, 0),
        expand(world, material, sx, sy, sz, 0, -1, 0),
        expand(world, material, sx, sy, sz, 0, 0, -1),
        expand(world, material, sx, sy, sz, 1, 0, 0),
        expand(world, material, sx, sy, sz, 0, 1, 0),
        expand(world, material, sx, sy, sz, 0, 0, 1)};
  }

  /** The nearest portal block within {@link #SEED_RADIUS} of the given block, or {@code null}. */
  private static Block findPortalBlock(World world, int x, int y, int z) {
    for (int dy = -SEED_RADIUS; dy <= SEED_RADIUS; dy++) {
      for (int dx = -SEED_RADIUS; dx <= SEED_RADIUS; dx++) {
        for (int dz = -SEED_RADIUS; dz <= SEED_RADIUS; dz++) {
          Block block = world.getBlockAt(x + dx, y + dy, z + dz);
          if (PORTAL_BLOCKS.contains(block.getType())) {
            return block;
          }
        }
      }
    }
    return null;
  }

  /** Walks from the seed along one direction while the material continues; returns the moving coord. */
  private static int expand(World world, Material material, int sx, int sy, int sz, int dx, int dy, int dz) {
    int cx = sx;
    int cy = sy;
    int cz = sz;
    for (int steps = 0; steps < MAX_EXPAND; steps++) {
      if (world.getBlockAt(cx + dx, cy + dy, cz + dz).getType() != material) {
        break;
      }
      cx += dx;
      cy += dy;
      cz += dz;
    }
    return dx != 0 ? cx : dy != 0 ? cy : cz;
  }
}
