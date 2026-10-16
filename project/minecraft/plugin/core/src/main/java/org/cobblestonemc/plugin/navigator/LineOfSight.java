/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

/** Whether a player can see a point: nothing but open blocks in between. */
public final class LineOfSight {

  private LineOfSight() {}

  /**
   * Whether every block the straight line from {@code eye} to {@code target} passes through, other
   * than the one the eyes are in, is open (an unreadable block counts as blocking).
   *
   * @param eye the player's eyes
   * @param target the point to see
   * @param probe reads the world
   * @return whether the line is clear
   */
  public static boolean clear(Vec3 eye, Vec3 target, TrailSmoother.BlockProbe probe) {
    // Amanatides–Woo: step block by block along the line, always across the nearest boundary.
    Vec3 delta = target.minus(eye);
    int x = floor(eye.x());
    int y = floor(eye.y());
    int z = floor(eye.z());
    int endX = floor(target.x());
    int endY = floor(target.y());
    int endZ = floor(target.z());
    int stepX = (int) Math.signum(delta.x());
    int stepY = (int) Math.signum(delta.y());
    int stepZ = (int) Math.signum(delta.z());
    double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / delta.x());
    double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / delta.y());
    double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / delta.z());
    double tMaxX = boundary(eye.x(), stepX) * tDeltaX;
    double tMaxY = boundary(eye.y(), stepY) * tDeltaY;
    double tMaxZ = boundary(eye.z(), stepZ) * tDeltaZ;
    int remaining = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z);
    for (int i = 0; i < remaining; i++) {
      if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
        x += stepX;
        tMaxX += tDeltaX;
      } else if (tMaxY <= tMaxZ) {
        y += stepY;
        tMaxY += tDeltaY;
      } else {
        z += stepZ;
        tMaxZ += tDeltaZ;
      }
      if (probe.at(x, y, z) != TrailBlock.OPEN) {
        return false;
      }
    }
    return true;
  }

  /** The distance from {@code coordinate} to the next block boundary in direction {@code step}. */
  private static double boundary(double coordinate, int step) {
    if (step == 0) {
      return Double.POSITIVE_INFINITY;
    }
    double block = Math.floor(coordinate);
    return step > 0 ? block + 1 - coordinate : coordinate - block;
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }
}
