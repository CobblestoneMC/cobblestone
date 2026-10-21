/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.navigator;

/**
 * Where a trail starts and stops around the player. The trail starts {@code leadBlocks} ahead of
 * them, or as far as they travel in {@code leadSeconds} if that is further; both 0 draws it from
 * their feet. It stops {@code goalClearance} short of a destination they can see.
 *
 * @param leadBlocks how far ahead the trail starts at the least, in blocks
 * @param leadSeconds how many seconds of travel at their speed it starts ahead
 * @param goalClearance how far short of a destination in sight it stops, in blocks
 */
public record TrailShape(double leadBlocks, double leadSeconds, double goalClearance) {

  /** The furthest ahead the trail starts, however fast the player goes, in blocks. */
  static final double MAX_LEAD = 32.0;

  /** Starts 3 blocks ahead, or a second's travel; stops 3 blocks short. */
  public static final TrailShape DEFAULT = new TrailShape(3, 1, 3);

  /** Clamps every value to zero or more. */
  public TrailShape {
    leadBlocks = Math.max(0, leadBlocks);
    leadSeconds = Math.max(0, leadSeconds);
    goalClearance = Math.max(0, goalClearance);
  }

  /**
   * How far ahead the trail starts for a player moving at {@code speed}.
   *
   * @param speed their speed, in blocks per second
   * @return the lead, in blocks
   */
  double lead(double speed) {
    return Math.max(leadBlocks, Math.min(leadSeconds * speed, MAX_LEAD));
  }
}
