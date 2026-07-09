/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.TraversalState;

/**
 * Walk a {@link VirtualPath} to a target transition's origin region, then
 * traverse that
 * transition. The edge cost is the virtual path's cost plus the transition's
 * cost; the head node
 * is
 * {@code AtTransition(targetTransition, targetTransition.apply(sourceState))}.
 *
 * @param <T>              the step-type enum
 * @param <I>              the instruction payload type
 * @param <D>              the domain type
 * @param virtualPath      the same-domain hop to the target's origin region
 * @param targetTransition the transition traversed at the end of the hop
 * @param sourceState      the accumulated state at the edge's source node
 */
record Tier1Edge<T extends Enum<T>, I, D extends Domain>(
        VirtualPath<T, I, D> virtualPath,
        Tier1Node<T, I, D> target,
        TraversalState sourceState) {
}
