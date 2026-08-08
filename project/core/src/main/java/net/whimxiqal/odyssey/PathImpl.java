/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey;

import net.whimxiqal.odyssey.api.Path;
import net.whimxiqal.odyssey.api.Step;

import java.util.List;

/**
 * The immutable {@link Path} implementation returned by a successful search.
 *
 * <p>{@link Path#cost()} and {@link Path#duration()} are derived from the steps by the interface
 * defaults, so this record stores only the step list.
 *
 * @param <T> the payload type
 * @param <D> the domain type
 * @param steps the ordered steps
 */
record PathImpl<T, D extends Domain>(List<Step<Position<D>, T>> steps)
    implements Path<Step<Position<D>, T>> {

  PathImpl {
    steps = List.copyOf(steps);
  }
}
