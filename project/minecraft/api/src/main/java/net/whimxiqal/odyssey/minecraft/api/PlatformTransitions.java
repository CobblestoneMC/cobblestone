/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.minecraft.api;

/**
 * Factories for the common case of a plain {@link PlatformTransition} — reach an origin region,
 * arrive at a destination, at a fixed cost. Saves every integration from hand-writing an identical
 * record. Platform-neutral; a platform may offer a typed convenience of its own (e.g. Paper's
 * {@code PaperTransition.of}) that delegates here.
 */
public final class PlatformTransitions {

  private PlatformTransitions() {}

  /**
   * A transition whose player-facing {@link PlatformTransition#time() time} equals its {@code
   * cost}.
   *
   * @param origin the origin region the agent must reach
   * @param destination the arrival location
   * @param cost the traversal cost/time in seconds
   * @param payload the step payload (e.g. {@link MinecraftStepPayload#portal()})
   * @param <R> the region type
   * @param <P> the position type
   * @return the transition
   */
  public static <R, P> PlatformTransition<R, P> of(
      R origin, P destination, double cost, MinecraftStepPayload payload) {
    return of(origin, destination, cost, cost, payload);
  }

  /**
   * A transition whose danger/penalty {@code cost} and real {@code time} differ.
   *
   * @param origin the origin region the agent must reach
   * @param destination the arrival location
   * @param cost the search cost in seconds (may include penalties)
   * @param time the real traversal time in seconds (player-facing)
   * @param payload the step payload
   * @param <R> the region type
   * @param <P> the position type
   * @return the transition
   */
  public static <R, P> PlatformTransition<R, P> of(
      R origin, P destination, double cost, double time, MinecraftStepPayload payload) {
    return new SimplePlatformTransition<>(origin, destination, cost, time, payload);
  }

  private record SimplePlatformTransition<R, P>(
      R origin, P destination, double cost, double time, MinecraftStepPayload payload)
      implements PlatformTransition<R, P> {}
}
