/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.TraversalState;

/**
 * A node in the Tier-1 transition graph.
 *
 * @param <T> the payload type
 * @param <D> the domain type
 */
sealed interface Tier1Node<T, D extends Domain>
    permits Tier1Node.Source, Tier1Node.AtTransition, Tier1Node.Sink {

  default double cost() {
    return 0.0;
  }

  record Source<T, D extends Domain>(Position<D> position, TraversalState state)
      implements Tier1Node<T, D> {
  }

  /**
   * Being located at a transition's destination, having arrived in a particular
   * accumulated state.
   * Value-based equality on {@code (transition, state)} keys the Dijkstra
   * frontier.
   *
   * @param <T>        the payload type
   * @param <D>        the domain type
   * @param transition the transition whose destination this node sits at
   * @param state      the accumulated traversal state on arrival
   */
  record AtTransition<T, D extends Domain>(
      Transition<T, D> transition, TraversalState state) implements Tier1Node<T, D> {
    @Override
    public double cost() {
      return transition.cost();
    }
  }

  /**
   * The synthetic super-sink; the Dijkstra goal. One instance per search, so
   * identity equality is
   * sufficient.
   *
   * @param <T> the payload type
   * @param <D> the domain type
   */
  record Sink<T, D extends Domain>() implements Tier1Node<T, D> {
  }
}
