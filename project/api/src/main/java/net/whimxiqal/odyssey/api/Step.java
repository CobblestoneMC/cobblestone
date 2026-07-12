/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

/**
 * One entry in a solved {@link Path}: where the agent is, how it got there, and the running cost.
 *
 * <p>Each step carries its own concrete {@link Domain} instance, so a caller reads the world
 * directly with no lookup. A step whose {@code stepType} is a transition type (and/or that carries
 * an {@code instruction}) marks a transition point; a domain change between consecutive steps marks
 * a crossing. The internal traversal state is deliberately not exposed here.
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <P> the position
 * @param position the cell occupied at this step and its domain
 * @param cumulativeCost the total cost in seconds to reach this step from the origin
 * @param stepType the step type
 * @param instruction an optional player instruction, or {@code null}
 */
public record Step<P, T extends Enum<T>, I>(
    P position,
    double cumulativeCost,
    T stepType,
    I instruction) {
}
