/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import java.util.List;

/**
 * Supplies the modes for one Tier-2 leg, given that leg's target region. Most modes are local and
 * target-agnostic, but a <i>goal-aware</i> mode — the ender-pearl "jump to the destination"
 * fail-safe — needs to know where the leg is headed. Passing a provider (rather than a fixed list)
 * to a search lets the platform inject the target into such modes per leg; {@link #of} wraps a
 * fixed list for the common target-agnostic case.
 *
 * @param <A> the agent type
 * @param <T> the payload type
 * @param <D> the domain type
 */
@FunctionalInterface
public interface ModesProvider<A extends Agent, T, D extends Domain> {

  /**
   * The modes to use for a leg targeting {@code target}.
   *
   * @param target the leg's target region
   * @return the modes available for that leg
   */
  List<? extends Mode<A, T, D>> modesFor(DomainRegion<D> target);

  /**
   * A provider that returns a fixed list regardless of the target.
   *
   * @param modes the modes
   * @param <A> the agent type
   * @param <T> the payload type
   * @param <D> the domain type
   * @return a target-agnostic provider
   */
  static <A extends Agent, T, D extends Domain> ModesProvider<A, T, D> of(
      List<? extends Mode<A, T, D>> modes) {
    List<? extends Mode<A, T, D>> copy = List.copyOf(modes);
    return target -> copy;
  }
}
