/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

/**
 * A block position.
 *
 * @param x the block's x-coordinate
 * @param y the block's y-coordinate
 * @param z the block's z-coordinate
 */
record BlockPos(int x, int y, int z) {

  /** The block containing {@code point}. */
  static BlockPos of(Vec3 point) {
    return new BlockPos(floor(point.x()), floor(point.y()), floor(point.z()));
  }

  /** The block directly above this one. */
  BlockPos above() {
    return new BlockPos(x, y + 1, z);
  }

  /** This position packed into a long (see {@link #pack(int, int, int)}). */
  long pack() {
    return pack(x, y, z);
  }

  /** Packs a block position into a long (26 bits x, 26 bits z, 12 bits y). */
  static long pack(int x, int y, int z) {
    return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
  }

  static int floor(double value) {
    return (int) Math.floor(value);
  }
}
