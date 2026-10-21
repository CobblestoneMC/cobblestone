/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.List;

/**
 * The stretch of a route the player is on: from them to the next teleport or the destination, as
 * smoothed trail segments, measured along its length.
 *
 * @param stretches the segments in order, the first starting where the player is
 * @param toGoal whether it runs to the destination
 * @param end the index of the first step past it, where later legs start
 * @param teleport whether it ends at a teleport, beyond which the route goes on
 */
record TrailLeg(List<Stretch> stretches, boolean toGoal, int end, boolean teleport) {

  /** How far past the furthest point the player can see the trail head may be, in blocks. */
  static final double STUB = 1.0;

  /**
   * Segment {@code segment} of {@code path}, from {@code from} (a fraction of the segment) to its
   * end.
   *
   * @param drawn whether particles are drawn along it (not for an action, such as opening a door)
   */
  record Stretch(SmoothedTrail path, int segment, double from, boolean drawn) {

    double length() {
      return path.segmentLength(segment) * (1 - from);
    }

    /** The fraction of the segment {@code arc} blocks along this stretch. */
    double fraction(double arc) {
      double length = length();
      return length == 0.0 ? from : from + (1 - from) * Math.clamp(arc / length, 0, 1);
    }
  }

  /** Receives one drawn part of a stretch: a segment of a trail between two fractions of it. */
  @FunctionalInterface
  interface Part {
    void draw(SmoothedTrail path, int segment, double from, double to, double length);
  }

  /** The leg's length, in blocks. */
  double length() {
    double length = 0;
    for (Stretch stretch : stretches) {
      length += stretch.length();
    }
    return length;
  }

  /** The point {@code arc} blocks along the leg, or its end if it is shorter. */
  Vec3 at(double arc) {
    for (Stretch stretch : stretches) {
      double length = stretch.length();
      if (arc <= length) {
        return stretch.path().sample(stretch.segment(), stretch.fraction(arc)).point();
      }
      arc -= length;
    }
    Stretch last = stretches.getLast();
    return last.path().sample(last.segment(), 1).point();
  }

  /**
   * Where along the leg its trail starts: {@code lead} blocks along, unless the player can't see
   * that point (around a corner, say), when it comes back to {@link #STUB} past the furthest point
   * at whole blocks along that they can see, leaving a stub around the corner.
   *
   * @param lead how far ahead the trail should start, in blocks
   * @param eye the player's eyes
   * @param probe reads the world
   * @return how far along the leg the trail starts, in blocks
   */
  double head(double lead, Vec3 eye, TrailSmoother.BlockProbe probe) {
    double head = Math.min(lead, length());
    for (double arc = head; arc >= 0; arc -= 1.0) {
      if (LineOfSight.clear(eye, at(arc), probe)) {
        return Math.min(head, arc + STUB);
      }
    }
    return Math.min(head, STUB);
  }

  /**
   * Hands {@code part} each drawn stretch, clipped to between {@code from} and {@code to} blocks
   * along the leg.
   *
   * @param from where to start, in blocks along
   * @param to where to stop, in blocks along
   * @param part receives each clipped stretch
   */
  void forEachDrawn(double from, double to, Part part) {
    double start = 0;
    for (Stretch stretch : stretches) {
      double length = stretch.length();
      double clippedFrom = Math.max(from, start);
      double clippedTo = Math.min(to, start + length);
      if (stretch.drawn() && clippedTo > clippedFrom) {
        part.draw(
            stretch.path(),
            stretch.segment(),
            stretch.fraction(clippedFrom - start),
            stretch.fraction(clippedTo - start),
            clippedTo - clippedFrom);
      }
      start += length;
    }
  }
}
