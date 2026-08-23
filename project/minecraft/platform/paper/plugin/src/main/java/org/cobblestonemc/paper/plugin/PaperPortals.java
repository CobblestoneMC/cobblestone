/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.paper.plugin;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.cobblestonemc.plugin.data.PortalRegion;

/**
 * Shared Bukkit portal-block scanning: given a location, find the portal it sits in and return its
 * block-coordinate box. Used by both the discovery listener and the normalization listener. Reads
 * blocks, so it must run on the location's owning thread.
 */
final class PaperPortals {

  private static final int SEED_RADIUS = 2; // where to look for the portal block near the location
  private static final int MAX_EXPAND = 64; // cap the outward scan (nether portals top out at ~23)

  private PaperPortals() {}

  /**
   * Scans the portal at {@code loc}: seeds on the nearest {@code material} block and expands
   * outward on every axis while that material continues, so a large portal is captured in full.
   * Falls back to a single cell at {@code loc} if none is found nearby.
   *
   * @param loc the location to scan around
   * @param material the portal block material
   * @return the portal's box (or a single cell at {@code loc})
   */
  static PortalRegion scanPortal(Location loc, Material material) {
    World world = loc.getWorld();
    String key = world.getKey().asString();
    Block seed =
        findPortalBlock(world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), material);
    if (seed == null) {
      int x = loc.getBlockX();
      int y = loc.getBlockY();
      int z = loc.getBlockZ();
      return new PortalRegion(key, x, y, z, x, y, z);
    }
    int sx = seed.getX();
    int sy = seed.getY();
    int sz = seed.getZ();
    return new PortalRegion(
        key,
        expand(world, material, sx, sy, sz, -1, 0, 0),
        expand(world, material, sx, sy, sz, 0, -1, 0),
        expand(world, material, sx, sy, sz, 0, 0, -1),
        expand(world, material, sx, sy, sz, 1, 0, 0),
        expand(world, material, sx, sy, sz, 0, 1, 0),
        expand(world, material, sx, sy, sz, 0, 0, 1));
  }

  /** The nearest {@code material} block within {@link #SEED_RADIUS}, or {@code null}. */
  private static Block findPortalBlock(World world, int x, int y, int z, Material material) {
    for (int dy = -SEED_RADIUS; dy <= SEED_RADIUS; dy++) {
      for (int dx = -SEED_RADIUS; dx <= SEED_RADIUS; dx++) {
        for (int dz = -SEED_RADIUS; dz <= SEED_RADIUS; dz++) {
          Block block = world.getBlockAt(x + dx, y + dy, z + dz);
          if (block.getType() == material) {
            return block;
          }
        }
      }
    }
    return null;
  }

  /**
   * Walks from the seed along one direction while the material continues; returns the moving coord.
   */
  private static int expand(
      World world, Material material, int sx, int sy, int sz, int dx, int dy, int dz) {
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
