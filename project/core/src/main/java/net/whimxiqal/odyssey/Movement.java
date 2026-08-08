/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.Step;
import net.whimxiqal.odyssey.api.TraversalState;

/**
 * The output unit of {@link Mode#step}: a single reachable neighbor and how the agent got there.
 *
 * <p>A movement carries no domain — it is always within the domain the mode was invoked on, and the
 * search stamps that domain onto the {@link Step} it builds. The {@code instruction} is
 * {@code null} unless this step requires the player to act.
 *
 * @param <T> the payload type
 * @param cell the reachable destination cell
 * @param cost the algorithm cost in seconds to perform this step (what the search minimizes)
 * @param time the real traversal time in seconds (player-facing); equals {@code cost} until danger
 *     weighting diverges them
 * @param payload the payload to send through to the search response
 * @param state the resulting traversal state after the step
 */
public record Movement<T>(
    Cell cell,
    double cost,
    double time,
    T payload,
    TraversalState state) {
}
