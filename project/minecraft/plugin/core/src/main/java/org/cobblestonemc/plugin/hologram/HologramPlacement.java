/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import java.util.List;
import java.util.Optional;
import org.cobblestonemc.plugin.navigator.LineOfSight;
import org.cobblestonemc.plugin.navigator.TrailBlock;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * Where a trip's label floats: over the trail, in a free block the player can see, and clear of the
 * player's other labels. A label is two lines, its trip's name with {@code Cancel} below it; it is
 * placed by the center of the name.
 */
final class HologramPlacement {

  /** How far above the trail (which runs at body height) the name floats, in blocks. */
  static final double HEIGHT = 0.5;

  /**
   * How far below the name its {@code Cancel} line floats: down at the trail itself, in front of
   * the trail head, so the player looks down to it, well below their eyes.
   */
  static final double CANCEL_DROP = 0.5;

  /** The vertical gap between two lines of text. */
  static final double LINE_SPACING = 0.3;

  /** The vertical gap between two labels stacked over the same spot: a Cancel, then a name. */
  static final double LABEL_SPACING = CANCEL_DROP + LINE_SPACING;

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
   * overlap. Both of the label's lines must be in open blocks with a clear line of sight from
   * {@code eye}.
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
      if (spot != null && visible(eye, spot, probe) && visible(eye, cancelAt(spot), probe)) {
        return Optional.of(spot);
      }
    }
    return Optional.empty();
  }

  /**
   * Where the {@code Cancel} line of the label at {@code at} floats.
   *
   * @param at the center of the label's name
   * @return the center of its {@code Cancel} line
   */
  static Vec3 cancelAt(Vec3 at) {
    return at.minus(new Vec3(0, CANCEL_DROP, 0));
  }

  /** Raises {@code spot} until it overlaps none of {@code taken}; {@code null} if it can't. */
  private static Vec3 unstack(Vec3 spot, List<Vec3> taken) {
    for (int i = 0; i < MAX_STACK; i++) {
      Vec3 raised = spot.plus(new Vec3(0, i * LABEL_SPACING, 0));
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
        && Math.abs(a.y() - b.y()) < LABEL_SPACING - 1e-6;
  }

  private static boolean visible(Vec3 eye, Vec3 point, TrailSmoother.BlockProbe probe) {
    return probe.at(floor(point.x()), floor(point.y()), floor(point.z())) == TrailBlock.OPEN
        && inSight(eye, point, probe);
  }

  /** See {@link LineOfSight#clear}. */
  static boolean inSight(Vec3 eye, Vec3 target, TrailSmoother.BlockProbe probe) {
    return LineOfSight.clear(eye, target, probe);
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }
}
