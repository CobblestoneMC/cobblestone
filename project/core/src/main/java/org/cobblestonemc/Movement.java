/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.function.Supplier;
import org.cobblestonemc.api.TraversalState;
import org.jetbrains.annotations.Nullable;

/**
 * A single reachable neighbor and how to get there, within the domain being traversed.
 *
 * <p>{@code restricted} lets an edge whose validity is not yet known be relaxed optimistically. The
 * supplier is invoked only when the search commits to the edge, and the edge is dropped if its
 * verdict resolves {@code true}.
 *
 * @param <T> the payload type
 * @param cell the reachable destination cell
 * @param cost the algorithm cost in seconds to perform this step (what the search minimizes)
 * @param time the real traversal time in seconds (player-facing); equals {@code cost} until danger
 *     weighting diverges them
 * @param payload the payload to send through to the search response
 * @param state the resulting traversal state after the step
 * @param restricted a supplier, invoked lazily at pop, of a verdict resolving {@code true} if this
 *     edge turns out to be disallowed; or {@code null} if the edge can never be restricted
 */
public record Movement<T>(
    Cell cell,
    double cost,
    double time,
    T payload,
    TraversalState state,
    @Nullable Supplier<FutureOr<Boolean>> restricted) {

  /** A movement that can never be restricted (no edge check). */
  public Movement(Cell cell, double cost, double time, T payload, TraversalState state) {
    this(cell, cost, time, payload, state, null);
  }
}
