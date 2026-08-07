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
 * <p>Each consecutive pair of points forms a segment. The player's position is projected onto the
 * foremost segment's direction; if the projection passes the segment's end, that segment is complete
 * and the foremost index advances. This tolerates a player cutting corners — they still "complete"
 * segments in the right order — without any exact-position tracking.
 */
public final class TrailProgress {

  private TrailProgress() {
  }

  /**
   * Advances the foremost index past every segment the player has already projected beyond.
   *
   * @param points the ordered trail points (path step positions)
   * @param foremost the index of the start of the segment currently being followed
   * @param player the player's current position
   * @return the new foremost index (never less than {@code foremost}, never past the last point)
   */
  public static int advance(List<Vec3> points, int foremost, Vec3 player) {
    int index = Math.max(0, foremost);
    while (index + 1 < points.size()) {
      Vec3 start = points.get(index);
      Vec3 segment = points.get(index + 1).minus(start);
      double lengthSquared = segment.lengthSquared();
      // A zero-length segment (duplicate point) is treated as already passed.
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
