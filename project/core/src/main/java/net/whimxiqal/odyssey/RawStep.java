/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.Step;

/**
 * An internal, per-step increment produced by a Tier-2 solve, before it is
 * flattened (with a running
 * global cost) into a public {@link Step}.
 *
 * @param <T>         the payload type
 * @param <D>         the domain type
 * @param position    the cell arrived at and its domain
 * @param stepCost    the incremental cost of this one step, in seconds
 * @param payload     the payload
 */
record RawStep<T, D extends Domain>(
        Position<D> position, double stepCost, T payload) {
}
