/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * A contiguous coordinate space (≈ a Minecraft world), used as a first-class object — there is no
 * id and no registry.
 *
 * <p>Implementations <b>must</b> provide stable value-based {@code equals} and {@code hashCode}
 * that identify the underlying world (Minecraft delegates to the world's {@code NamespacedKey}),
 * because a {@code Domain} participates in {@link Position} equality and is used as a map key.
 *
 * <p>An embedder must use exactly one concrete {@code Domain} type and distinguish dimensions with
 * a field/method rather than with subtypes; the single domain type is threaded through the search
 * as the {@code D} generic so results hand back concrete world objects without a cast.
 */
public interface Domain {

  /**
   * Returns the cells this domain holds, along each axis it bounds.
   *
   * <p>Core assumes no shape: a Minecraft world bounds Y and leaves X and Z open, and another
   * domain may bound any axis, or none.
   *
   * @return the bounds
   */
  DomainBounds bounds();

  /**
   * Returns whether the given cell lies within this domain.
   *
   * @param cell the cell to test
   * @return {@code true} if it is within {@link #bounds()}
   */
  default boolean contains(Cell cell) {
    return bounds().contains(cell);
  }
}
