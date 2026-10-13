/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import java.util.List;
import java.util.Optional;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * Where a trip's label floats: over the trail, in a free block the player can see, and clear of the
 * player's other labels.
 */
final class HologramPlacement {

  /** How far above the trail (which runs at body height) the label floats, in blocks. */
  static final double HEIGHT = 1.0;

  /** The vertical gap between two labels stacked over the same spot. */
  static final double LINE_SPACING = 0.3;

  /** Labels closer than this horizontally are stacked. */
  private static final double STACK_RADIUS = 0.75;

  private static final int MAX_STACK = 8;

  /**
   * Block offsets tried in order when the spot over the trail is blocked: straight up, then each
   * side, then each side one block up.
   */
  private static final List<int[]> NEIGHBORS =
      List.of(
          new int[] {0, 0, 0},
          new int[] {0, 1, 0},
          new int[] {1, 0, 0},
          new int[] {-1, 0, 0},
          new int[] {0, 0, 1},
          new int[] {0, 0, -1},
          new int[] {1, 1, 0},
          new int[] {-1, 1, 0},
          new int[] {0, 1, 1},
          new int[] {0, 1, -1});

  private HologramPlacement() {}

  /**
   * Finds where to put a label over {@code trail}: {@link #HEIGHT} above it or, if that block is
   * solid, the nearest free block beside or above, raised over any {@code taken} label it would
   * overlap. The spot must be in an open block with a clear line of sight from {@code eye}.
   *
   * @param eye the player's eyes
   * @param trail the point on the trail the label belongs to
   * @param taken where the player's other labels are
   * @param probe reads the world
   * @return the label's center, or empty if there is no such spot
   */
  static Optional<Vec3> place(
      Vec3 eye, Vec3 trail, List<Vec3> taken, TrailSmoother.BlockProbe probe) {
    Vec3 over = trail.plus(new Vec3(0, HEIGHT, 0));
    for (int[] offset : NEIGHBORS) {
      Vec3 spot = unstack(over.plus(new Vec3(offset[0], offset[1], offset[2])), taken);
      if (spot != null && open(spot, probe) && inSight(eye, spot, probe)) {
        return Optional.of(spot);
      }
    }
    return Optional.empty();
  }

  /** Raises {@code spot} until it overlaps none of {@code taken}; {@code null} if it can't. */
  private static Vec3 unstack(Vec3 spot, List<Vec3> taken) {
    for (int i = 0; i < MAX_STACK; i++) {
      Vec3 raised = spot.plus(new Vec3(0, i * LINE_SPACING, 0));
      if (taken.stream().noneMatch(other -> overlaps(raised, other))) {
        return raised;
      }
    }
    return null;
  }

  private static boolean overlaps(Vec3 a, Vec3 b) {
    double dx = a.x() - b.x();
    double dz = a.z() - b.z();
    return dx * dx + dz * dz < STACK_RADIUS * STACK_RADIUS
        && Math.abs(a.y() - b.y()) < LINE_SPACING - 1e-6;
  }

  private static boolean open(Vec3 point, TrailSmoother.BlockProbe probe) {
    return probe.at(floor(point.x()), floor(point.y()), floor(point.z())) == TrailBlock.OPEN;
  }

  /**
   * Whether every block the straight line from {@code eye} to {@code target} passes through, other
   * than the one the eyes are in, is open (an unreadable block counts as blocking).
   */
  static boolean inSight(Vec3 eye, Vec3 target, TrailSmoother.BlockProbe probe) {
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
