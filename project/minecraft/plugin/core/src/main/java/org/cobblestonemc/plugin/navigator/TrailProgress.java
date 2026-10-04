/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

import java.util.List;

/**
 * The platform-neutral follow logic for a trail navigator: given the trail's nodes and the player's
 * current position, decide how far along the trail the player has gotten.
 *
 * <p>Node 0 is where the first step departs from (the player's start), and step {@code i} runs from
 * node {@code i} to node {@code i + 1}. {@code foremost} is the index of the step the player still
 * needs to complete (0 = the first step, not yet done); the player completes it by projecting past
 * its destination, and {@code foremost} advances. This tolerates a player cutting corners — they
 * still "complete" steps in order — without any exact-position tracking.
 */
public final class TrailProgress {

  private TrailProgress() {}

  /**
   * Advances the foremost index past every step the player has already projected beyond.
   *
   * @param nodes the trail's nodes: the origin, then each step's destination
   * @param foremost the index of the step the player still needs to complete
   * @param player the player's current position
   * @return the new foremost index (never below {@code foremost}; {@code nodes.size() - 1} once
   *     every step is complete)
   */
  public static int advance(List<Vec3> nodes, int foremost, Vec3 player) {
    int index = Math.max(0, foremost);
    while (index + 1 < nodes.size()) {
      Vec3 start = nodes.get(index);
      Vec3 segment = nodes.get(index + 1).minus(start);
      double lengthSquared = segment.lengthSquared();
      // A zero-length segment (duplicate point / standing on the origin) is treated as passed.
      double projection =
          lengthSquared == 0.0 ? 1.0 : player.minus(start).dot(segment) / lengthSquared;
      if (projection >= 1.0) {
        index++;
      } else {
        break;
      }
    }
    return index;
  }
}
