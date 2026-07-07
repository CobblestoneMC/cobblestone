/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

/**
 * A one-directional single-step "jump" between two places — a nether portal, a {@code /home}
 * teleport, a horse mount, etc.
 *
 * <p>Its {@link #origin()} is an entry area ({@link DomainRegion}) and its {@link #destination()} a
 * single arrival {@link Position}. Origin and destination share the domain <i>type</i> {@code D}
 * but are usually different world <i>instances</i> (Overworld → Nether). A transition may transform
 * the {@link TraversalState} on traversal (e.g. mounting a horse) and may carry an instruction the
 * player must perform (e.g. run a command).
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 */
public interface Transition<T extends Enum<T>, I, D extends Domain> {

  /**
   * Returns the entry area the agent must reach to use this transition.
   *
   * @return the origin region
   */
  DomainRegion<D> origin();

  /**
   * Returns the point the agent arrives at after traversing this transition.
   *
   * @return the destination position
   */
  Position<D> destination();

  /**
   * Returns the traversal cost in seconds.
   *
   * @return the cost
   */
  double cost();

  /**
   * Returns the step type for this transition (e.g. {@code PORTAL}, {@code COMMAND},
   * {@code MOUNT_HORSE}).
   *
   * @return the step type
   */
  T stepType();

  /**
   * Returns the instruction the player must perform to traverse this transition, or {@code null}
   * for an automatic one (e.g. walking through a portal).
   *
   * @return the instruction, or {@code null}
   */
  I instruction();

  /**
   * Transforms the incoming traversal state on traversal. The default is the identity (a plain
   * teleport changes nothing); a horse-mount transition sets the vehicle state, for example.
   *
   * @param in the state before traversal
   * @return the state after traversal
   */
  default TraversalState apply(TraversalState in) {
    return in;
  }
}
