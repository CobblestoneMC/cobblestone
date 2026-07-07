/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.TraversalState;
import net.whimxiqal.odyssey.api.Transition;

/**
 * A node in the Tier-1 transition graph.
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
sealed interface Tier1Node<T extends Enum<T>, I, D extends Domain>
    permits Tier1Node.AtTransition, Tier1Node.Sink {

  /**
   * Being located at a transition's destination, having arrived in a particular accumulated state.
   * Value-based equality on {@code (transition, state)} keys the Dijkstra frontier.
   *
   * @param <T> the step-type enum
   * @param <I> the instruction payload type
   * @param <D> the domain type
   * @param transition the transition whose destination this node sits at
   * @param state the accumulated traversal state on arrival
   */
  record AtTransition<T extends Enum<T>, I, D extends Domain>(
      Transition<T, I, D> transition, TraversalState state) implements Tier1Node<T, I, D> {
  }

  /**
   * The synthetic super-sink; the Dijkstra goal. One instance per search, so identity equality is
   * sufficient.
   *
   * @param <T> the step-type enum
   * @param <I> the instruction payload type
   * @param <D> the domain type
   */
  final class Sink<T extends Enum<T>, I, D extends Domain> implements Tier1Node<T, I, D> {
  }
}
