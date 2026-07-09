/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.Position;

/**
 * An internal, per-step increment produced by a Tier-2 solve, before it is
 * flattened (with a running
 * global cost) into a public {@link net.whimxiqal.odyssey.api.Step}.
 *
 * @param <T>         the step-type enum
 * @param <I>         the instruction payload type
 * @param <D>         the domain type
 * @param position    the cell arrived at and its domain
 * @param stepCost    the incremental cost of this one step, in seconds
 * @param stepType    the step type
 * @param instruction an optional instruction, or {@code null}
 */
record RawStep<T extends Enum<T>, I, D extends Domain>(
        Position<D> position, double stepCost, T stepType, I instruction) {
}
