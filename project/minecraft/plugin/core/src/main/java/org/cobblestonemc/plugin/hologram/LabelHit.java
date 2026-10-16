/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.hologram;

import java.util.OptionalDouble;
import org.cobblestonemc.plugin.navigator.TrailSmoother;
import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * Whether a player's swing lands on a label. Labels have no hitbox the client knows of — a client
 * only reaches 3 blocks in survival, and labels float further out than that — so the server traces
 * the player's line of sight itself.
 *
 * <p>A label turns to face the player, so it is taken as an upright rectangle through its center,
 * square to the horizontal line from it to the player's eyes.
 */
final class LabelHit {

  /** How far away a label can be hit from, in blocks. */
  static final double REACH = 32.0;

  /** How wide one character of label text is drawn, in blocks: 6 pixels at 0.025 each. */
  private static final double CHARACTER_WIDTH = 0.15;

  /** How far around its text a label can still be hit, in blocks. */
  private static final double MARGIN = 0.1;

  /** Half the height a label can be hit within: a 9-pixel line, plus the margin. */
  static final double HALF_HEIGHT = 9 * 0.025 / 2 + MARGIN / 2;

  private LabelHit() {}

  /**
   * Half the width a label of {@code characters} characters can be hit within.
   *
   * @param characters the length of the label's text
   * @return the half-width, in blocks
   */
  static double halfWidth(int characters) {
    return characters * CHARACTER_WIDTH / 2 + MARGIN;
  }

  /**
   * How far along the player's line of sight it meets the label centered at {@code center}, if it
   * does within {@link #REACH} and no block is in the way.
   *
   * @param eye the player's eyes
   * @param look the unit direction they look in
   * @param center the label's center
   * @param halfWidth half the label's width
   * @param probe reads the world
   * @return the distance from the eyes to where the line meets the label, or empty for a miss
   */
  static OptionalDouble distance(
      Vec3 eye, Vec3 look, Vec3 center, double halfWidth, TrailSmoother.BlockProbe probe) {
    Vec3 toEye = new Vec3(eye.x() - center.x(), 0, eye.z() - center.z());
    if (toEye.lengthSquared() < 1e-6) {
      return OptionalDouble.empty(); // straight above or below: the label is seen edge-on
    }
    Vec3 normal = toEye.unit();
    double facing = look.dot(normal);
    if (facing >= 0) {
      return OptionalDouble.empty(); // looking away from the label's plane
    }
    double distance = center.minus(eye).dot(normal) / facing;
    if (distance <= 0 || distance > REACH) {
      return OptionalDouble.empty();
    }
    Vec3 hit = eye.plus(look.times(distance));
    Vec3 offset = hit.minus(center);
    double across = offset.x() * -normal.z() + offset.z() * normal.x();
    if (Math.abs(across) > halfWidth
        || Math.abs(offset.y()) > HALF_HEIGHT
        || !HologramPlacement.inSight(eye, hit, probe)) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(distance);
  }
}
