/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

/**
 * Smooths the trail's block-to-block polyline so particles round each change in direction instead
 * of turning a hard corner.
 *
 * <p>Each corner (a point where two segments meet) is replaced by a quadratic Bézier whose control
 * point is the corner itself and whose endpoints sit {@code min(segment / 2, MAX_CORNER_RADIUS)}
 * back along each adjoining segment; the rest of each segment stays straight. The curve is tangent
 * to both segments where it joins them, so the spawn point and the flow direction change
 * continuously. A Bézier stays within the convex hull of its control points, so the rounded corner
 * never bulges outside the corner it cuts; it does cut inside the corner, toward whatever lies
 * there, so {@link TrailSmoother} keeps these curves (not just the segments) clear of blocks.
 *
 * <p>A segment is sampled on its own ({@link #sample}) given its neighbors, and both segments that
 * share a corner compute the same curve for it, so adjacent segments join seamlessly. Half of each
 * corner curve belongs to the segment on either side.
 */
final class TrailCurve {

  /** The furthest a corner curve reaches back along a segment, in blocks. */
  static final double MAX_CORNER_RADIUS = 0.5;

  private static final double EPSILON = 1e-9;

  /**
   * A point on the trail and the (unit, or zero if undefined) direction of travel there.
   *
   * @param point the position
   * @param direction the unit tangent, or {@link Vec3#ZERO} for a zero-length segment
   */
  record Sample(Vec3 point, Vec3 direction) {}

  private TrailCurve() {}

  /**
   * Samples the smoothed trail along the segment {@code from → to}.
   *
   * @param prev the point before {@code from}, or {@code null} to leave the start corner sharp
   *     (e.g. the path's origin, or after a teleport)
   * @param from the segment start
   * @param to the segment end
   * @param next the point after {@code to}, or {@code null} to leave the end corner sharp
   * @param fraction how far along the segment, from 0 (at {@code from}) to 1 (at {@code to})
   * @return the smoothed position and direction of travel
   */
  static Sample sample(Vec3 prev, Vec3 from, Vec3 to, Vec3 next, double fraction) {
    Vec3 diff = to.minus(from);
    double length = diff.length();
    if (length < EPSILON) {
      return new Sample(from, Vec3.ZERO);
    }
    Vec3 dir = diff.times(1 / length);
    double distance = Math.clamp(fraction, 0.0, 1.0) * length;
    double ownTrim = Math.min(length / 2, MAX_CORNER_RADIUS);

    // Second half of the curve around `from`: t runs 0.5 → 1 over the first `ownTrim` blocks.
    if (prev != null && distance < ownTrim) {
      Vec3 start = trimPoint(from, prev);
      if (start != null) {
        return quadratic(start, from, trimPoint(from, to), 0.5 + 0.5 * distance / ownTrim, dir);
      }
    }
    // First half of the curve around `to`: t runs 0 → 0.5 over the last `ownTrim` blocks.
    if (next != null && distance > length - ownTrim) {
      Vec3 end = trimPoint(to, next);
      if (end != null) {
        return quadratic(
            trimPoint(to, from), to, end, 0.5 * (distance - (length - ownTrim)) / ownTrim, dir);
      }
    }
    return new Sample(from.plus(dir.times(distance)), dir);
  }

  /**
   * A point on the rounded corner at {@code corner} between {@code prev} and {@code next}: the same
   * curve {@link #sample} draws there, from where it leaves the segment from {@code prev} ({@code t
   * = 0}) to where it joins the segment to {@code next} ({@code t = 1}).
   *
   * @return the point, or {@code null} if either segment has zero length (the corner isn't rounded)
   */
  static Vec3 corner(Vec3 prev, Vec3 corner, Vec3 next, double t) {
    Vec3 start = trimPoint(corner, prev);
    Vec3 end = trimPoint(corner, next);
    if (start == null || end == null) {
      return null;
    }
    return quadratic(start, corner, end, t, Vec3.ZERO).point();
  }

  /**
   * Where a corner's curve meets the segment from {@code corner} toward {@code other}, or {@code
   * null} if that segment has zero length.
   */
  private static Vec3 trimPoint(Vec3 corner, Vec3 other) {
    Vec3 diff = other.minus(corner);
    double length = diff.length();
    if (length < EPSILON) {
      return null;
    }
    return corner.plus(diff.times(Math.min(length / 2, MAX_CORNER_RADIUS) / length));
  }

  /**
   * Evaluates the quadratic Bézier {@code start, control, end} at {@code t}.
   *
   * @param fallback the direction to report where the tangent vanishes (a full reversal)
   */
  private static Sample quadratic(Vec3 start, Vec3 control, Vec3 end, double t, Vec3 fallback) {
    double u = 1 - t;
    Vec3 point = start.times(u * u).plus(control.times(2 * u * t)).plus(end.times(t * t));
    // B'(t) = 2(1 - t)(control - start) + 2t(end - control)
    Vec3 tangent = control.minus(start).times(u).plus(end.minus(control).times(t));
    Vec3 direction = tangent.lengthSquared() < EPSILON ? fallback : tangent.unit();
    return new Sample(point, direction);
  }
}
