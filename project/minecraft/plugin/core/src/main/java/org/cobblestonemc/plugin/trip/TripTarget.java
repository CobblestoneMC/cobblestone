/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.plugin.trip;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.cobblestonemc.plugin.navigator.Vec3;

/**
 * What a trip knows about the thing it leads to, beyond the route: whether it still exists, whether
 * it already shows its name, and where it is right now.
 *
 * @param existence whether it still exists; the trip ends once this returns {@code false}. Polled
 *     about once a second on the trip's thread, so it must be cheap
 * @param labeled whether it already shows its name in the world, as a player's name tag does
 * @param anchor where a label naming it floats right now (just above it), read on the trip's thread
 *     as often as every tick; empty to float the label over the end of the route instead, as for a
 *     place that never moves
 */
public record TripTarget(
    BooleanSupplier existence, boolean labeled, Supplier<Optional<Point>> anchor) {

  /** A target that always exists, shows no name, and is where the route ends. */
  public static final TripTarget FIXED = new TripTarget(() -> true, false, Optional::empty);

  /** Canonical constructor; everything is required. */
  public TripTarget {
    Objects.requireNonNull(existence, "existence");
    Objects.requireNonNull(anchor, "anchor");
  }

  /**
   * Returns whether the target still exists.
   *
   * @return {@code false} once it is gone for good
   */
  public boolean exists() {
    return existence.getAsBoolean();
  }

  /**
   * A point in a world.
   *
   * @param world the world's key
   * @param at the point
   */
  public record Point(String world, Vec3 at) {}
}
