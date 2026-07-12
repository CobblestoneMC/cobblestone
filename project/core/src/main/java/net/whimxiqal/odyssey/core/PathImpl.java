/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import java.util.List;
import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Position;
import net.whimxiqal.odyssey.api.Step;

/**
 * The immutable {@link Path} implementation returned by a successful search.
 *
 * @param <T> the step-type enum
 * @param <I> the instruction payload type
 * @param <D> the domain type
 * @param steps the ordered steps
 * @param cost the total cost in seconds
 */
record PathImpl<T extends Enum<T>, I, D extends Domain>(List<Step<Position<D>, T, I>> steps, double cost)
    implements Path<Step<Position<D>, T, I>> {

  PathImpl {
    steps = List.copyOf(steps);
  }
}
