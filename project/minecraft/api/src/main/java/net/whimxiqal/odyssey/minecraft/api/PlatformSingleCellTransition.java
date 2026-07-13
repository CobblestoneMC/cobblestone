/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

import net.whimxiqal.odyssey.api.TraversalState;

/**
 * A developer-facing transition expressed in native platform terms: a one-step jump from one
 * location to another (a portal, a {@code /home} teleport, a mount, …).
 *
 * <p>Unlike the generic core {@link net.whimxiqal.odyssey.api.Transition}, whose origin is a region,
 * this exposes a single {@link #origin()} location — the common case for developer-supplied
 * transitions. The platform layer adapts it into a core transition.
 *
 * @param <L> the native location type (e.g. {@code org.bukkit.Location})
 */
public interface PlatformSingleCellTransition<L> {

  /**
   * Returns the location the agent must reach to use this transition.
   *
   * @return the origin location
   */
  L origin();

  /**
   * Returns the location the agent arrives at after traversing this transition.
   *
   * @return the destination location
   */
  L destination();

  /**
   * Returns the traversal cost in seconds.
   *
   * @return the cost
   */
  double cost();

  /**
   * Returns the payload for this transition (e.g. {@code PORTAL}, {@code COMMAND} step type).
   *
   * @return the payload
   */
  MinecraftStepPayload payload();

  /**
   * Transforms the incoming traversal state on traversal; the default is the identity.
   *
   * @param in the state before traversal
   * @return the state after traversal
   */
  default TraversalState apply(TraversalState in) {
    return in;
  }
}
