/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.sponge12.plugin;

import net.whimxiqal.odyssey.plugin.data.PortalRegion;
import org.spongepowered.api.block.BlockType;
import org.spongepowered.api.world.server.ServerWorld;

/**
 * Shared Sponge portal-block scanning: given a world and a seed block, find the portal it sits in
 * and return its block-coordinate box. Reads blocks, so it must run on the server thread.
 */
final class SpongePortals {

  private static final int SEED_RADIUS = 2; // where to look for the portal block near the location
  private static final int MAX_EXPAND = 64; // cap the outward scan (nether portals top out at ~23)

  private SpongePortals() {}

  /**
   * Scans the portal at {@code (x, y, z)}: seeds on the nearest {@code material} block and expands
   * outward on every axis while that material continues. Falls back to a single cell if none found.
   *
   * @param world the world to read
   * @param x the seed block x
   * @param y the seed block y
   * @param z the seed block z
   * @param material the portal block type
   * @return the portal's box (or a single cell at the seed)
   */
  static PortalRegion scanPortal(ServerWorld world, int x, int y, int z, BlockType material) {
    String key = world.key().asString();
    int[] seed = findPortalBlock(world, x, y, z, material);
    if (seed == null) {
      return new PortalRegion(key, x, y, z, x, y, z);
    }
    int sx = seed[0];
    int sy = seed[1];
    int sz = seed[2];
    return new PortalRegion(
        key,
        expand(world, material, sx, sy, sz, -1, 0, 0),
        expand(world, material, sx, sy, sz, 0, -1, 0),
        expand(world, material, sx, sy, sz, 0, 0, -1),
        expand(world, material, sx, sy, sz, 1, 0, 0),
        expand(world, material, sx, sy, sz, 0, 1, 0),
        expand(world, material, sx, sy, sz, 0, 0, 1));
  }

  /**
   * The nearest {@code material} block within {@link #SEED_RADIUS}, as {@code {x,y,z}}, or null.
   */
  private static int[] findPortalBlock(ServerWorld world, int x, int y, int z, BlockType material) {
    for (int dy = -SEED_RADIUS; dy <= SEED_RADIUS; dy++) {
      for (int dx = -SEED_RADIUS; dx <= SEED_RADIUS; dx++) {
        for (int dz = -SEED_RADIUS; dz <= SEED_RADIUS; dz++) {
          if (matches(world, x + dx, y + dy, z + dz, material)) {
            return new int[] {x + dx, y + dy, z + dz};
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
      ServerWorld world, BlockType material, int sx, int sy, int sz, int dx, int dy, int dz) {
    int cx = sx;
    int cy = sy;
    int cz = sz;
    for (int steps = 0; steps < MAX_EXPAND; steps++) {
      if (!matches(world, cx + dx, cy + dy, cz + dz, material)) {
        break;
      }
      cx += dx;
      cy += dy;
      cz += dz;
    }
    return dx != 0 ? cx : dy != 0 ? cy : cz;
  }

  /** Whether the block at the given coordinates is the material (false if outside build height). */
  private static boolean matches(ServerWorld world, int x, int y, int z, BlockType material) {
    if (y < world.min().y() || y > world.max().y()) {
      return false;
    }
    return world.block(x, y, z).type().equals(material);
  }
}
