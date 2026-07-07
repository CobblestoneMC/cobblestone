/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.api;

/**
 * The output unit of {@link Mode#step}: a single reachable neighbor and how the agent got there.
 *
 * <p>A movement carries no domain — it is always within the domain the mode was invoked on, and the
 * search stamps that domain onto the {@link Step} it builds. The {@code instruction} is
 * {@code null} unless this step requires the player to act.
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param cell the reachable destination cell
 * @param cost the cost in seconds to perform this step
 * @param stepType the step type (usually the mode's primary type; may differ, e.g. boat entry)
 * @param state the resulting traversal state after the step
 * @param instruction an optional player instruction, or {@code null}
 */
public record Movement<T extends Enum<T>, I>(
    Cell cell,
    double cost,
    T stepType,
    TraversalState state,
    I instruction) {
}
