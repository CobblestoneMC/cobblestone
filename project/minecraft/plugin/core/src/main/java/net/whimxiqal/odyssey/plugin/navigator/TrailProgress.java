/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.plugin.navigator;

import java.util.List;

/**
 * The platform-neutral follow logic for a trail navigator: given the ordered path points and the
 * player's current position, decide how far along the trail the player has gotten.
 *
 * <p>{@code points} are the step destinations, index-aligned with the path's steps, and
 * {@code foremost} is the index of the step the player still needs to complete (0 = the first step,
 * not yet done). Step {@code foremost} runs from {@code points[foremost - 1]} — or the passed
 * {@code origin} when {@code foremost == 0} — to {@code points[foremost]}; the player completes it by
 * projecting past that destination, and {@code foremost} advances. This tolerates a player cutting
 * corners — they still "complete" steps in order — without any exact-position tracking.
 */
public final class TrailProgress {

  private TrailProgress() {
  }

  /**
   * Advances the foremost index past every step the player has already projected beyond.
   *
   * @param points the ordered step destinations (index-aligned with the path's steps)
   * @param origin the position the first step departs from (the player's start)
   * @param foremost the index of the step the player still needs to complete
   * @param player the player's current position
   * @return the new foremost index (never below {@code foremost}; {@code points.size()} once every
   *     step is complete)
   */
  public static int advance(List<Vec3> points, Vec3 origin, int foremost, Vec3 player) {
    int index = Math.max(0, foremost);
    while (index < points.size()) {
      Vec3 start = index == 0 ? origin : points.get(index - 1);
      Vec3 segment = points.get(index).minus(start);
      double lengthSquared = segment.lengthSquared();
      // A zero-length segment (duplicate point / standing on the origin) is treated as passed.
      double projection = lengthSquared == 0.0 ? 1.0 : player.minus(start).dot(segment) / lengthSquared;
      if (projection >= 1.0) {
        index++;
      } else {
        break;
      }
    }
    return index;
  }
}
