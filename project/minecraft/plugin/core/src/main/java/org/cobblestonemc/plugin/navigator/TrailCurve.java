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
 * continuously. Because a Bézier stays within the convex hull of its control points, the rounded
 * corner never bulges outside the corner it cuts — particles don't swing into walls.
 *
 * <p>A segment is sampled on its own ({@link #sample}) given its neighbors, and both segments that
 * share a corner compute the same curve for it, so adjacent segments join seamlessly. Half of each
 * corner curve belongs to the segment on either side.
 */
public final class TrailCurve {

  /** The furthest a corner curve reaches back along a segment, in blocks. */
  static final double MAX_CORNER_RADIUS = 0.5;

  private static final double EPSILON = 1e-9;

  /**
   * A point on the trail and the (unit, or zero if undefined) direction of travel there.
   *
   * @param point the position
   * @param direction the unit tangent, or {@link Vec3#ZERO} for a zero-length segment
   */
  public record Sample(Vec3 point, Vec3 direction) {}

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
  public static Sample sample(Vec3 prev, Vec3 from, Vec3 to, Vec3 next, double fraction) {
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
      Vec3 in = from.minus(prev);
      double inLength = in.length();
      if (inLength >= EPSILON) {
        double inTrim = Math.min(inLength / 2, MAX_CORNER_RADIUS);
        Vec3 start = from.minus(in.times(inTrim / inLength));
        Vec3 end = from.plus(dir.times(ownTrim));
        return quadratic(start, from, end, 0.5 + 0.5 * distance / ownTrim, dir);
      }
    }
    // First half of the curve around `to`: t runs 0 → 0.5 over the last `ownTrim` blocks.
    if (next != null && distance > length - ownTrim) {
      Vec3 out = next.minus(to);
      double outLength = out.length();
      if (outLength >= EPSILON) {
        double outTrim = Math.min(outLength / 2, MAX_CORNER_RADIUS);
        Vec3 start = to.minus(dir.times(ownTrim));
        Vec3 end = to.plus(out.times(outTrim / outLength));
        return quadratic(start, to, end, 0.5 * (distance - (length - ownTrim)) / ownTrim, dir);
      }
    }
    return new Sample(from.plus(dir.times(distance)), dir);
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
