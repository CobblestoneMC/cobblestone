/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import java.util.function.Supplier;
import org.cobblestonemc.api.Step;
import org.cobblestonemc.api.TraversalState;
import org.jetbrains.annotations.Nullable;

/**
 * The output unit of {@link Mode#step}: a single reachable neighbor and how the agent got there.
 *
 * <p>A movement carries no domain — it is always within the domain the mode was invoked on, and the
 * search stamps that domain onto the {@link Step} it builds. The {@code instruction} is {@code
 * null} unless this step requires the player to act.
 *
 * <p>{@code restricted} is the mode-scoped, optimistic counterpart to the search's global
 * passability restrictions: a mode that produces an edge whose validity it cannot yet confirm (the
 * mining mode, whose blocks a protection plugin may forbid breaking; the ender-pearl mode, whose
 * ballistic path must be clear) attaches a <b>supplier</b> here. The check is <i>lazy</i> — the
 * supplier is not invoked until the search pops the edge, so the (possibly expensive) integration
 * query or block scan runs only for edges the search actually commits to. The search relaxes the
 * edge optimistically and, if the supplied {@link FutureOr} later resolves {@code true}
 * (restricted), drops just this edge. {@code null} — the overwhelmingly common case (walking,
 * flying, …) — means "never restricted" and allocates nothing.
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

  /** A movement that can never be restricted (no mode-scoped edge check). */
  public Movement(Cell cell, double cost, double time, T payload, TraversalState state) {
    this(cell, cost, time, payload, state, null);
  }
}
