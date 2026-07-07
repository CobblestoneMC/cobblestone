/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.DomainRegion;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.Transition;

/**
 * An internal, zero-cost bookend {@link Transition} used only inside the Tier-1 graph:
 *
 * <ul>
 *   <li>an <b>origin</b> transition whose destination is the player's start position (the Dijkstra
 *       source; its {@link #origin()} is never queried), and</li>
 *   <li>a <b>destination</b> transition whose origin is a goal region (its {@link #destination()} is
 *       never queried; it links only to the super-sink).</li>
 * </ul>
 *
 * <p>Synthetic transitions are recognized by reference (this class) and contribute no step to the
 * flattened result — there is no public {@code isPseudo} flag on {@link Transition}.
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
final class SyntheticTransition<T extends Enum<T>, I, D extends Domain> implements Transition<T, I, D> {

  private final DomainRegion<D> origin;
  private final Position<D> destination;

  private SyntheticTransition(DomainRegion<D> origin, Position<D> destination) {
    this.origin = origin;
    this.destination = destination;
  }

  static <T extends Enum<T>, I, D extends Domain> SyntheticTransition<T, I, D> origin(
      Position<D> playerPosition) {
    return new SyntheticTransition<>(null, playerPosition);
  }

  static <T extends Enum<T>, I, D extends Domain> SyntheticTransition<T, I, D> destination(
      DomainRegion<D> goalRegion) {
    return new SyntheticTransition<>(goalRegion, null);
  }

  @Override
  public DomainRegion<D> origin() {
    return origin;
  }

  @Override
  public Position<D> destination() {
    return destination;
  }

  @Override
  public double cost() {
    return 0.0;
  }

  @Override
  public T stepType() {
    return null;
  }

  @Override
  public I instruction() {
    return null;
  }
}
