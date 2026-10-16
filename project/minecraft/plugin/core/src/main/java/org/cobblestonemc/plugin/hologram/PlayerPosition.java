/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * Where a player stands, sees from, and looks.
 *
 * @param feet the player's position
 * @param eye the position of their eyes
 * @param look the unit direction they are looking in
 */
public record PlayerPosition(Vec3 feet, Vec3 eye, Vec3 look) {

  /**
   * Whether the player's feet are in the same block as {@code other}'s.
   *
   * @param other another position
   * @return whether they are in the same block
   */
  public boolean sameBlock(PlayerPosition other) {
    return block(feet.x()) == block(other.feet.x())
        && block(feet.y()) == block(other.feet.y())
        && block(feet.z()) == block(other.feet.z());
  }

  private static int block(double coordinate) {
    return (int) Math.floor(coordinate);
  }
}
