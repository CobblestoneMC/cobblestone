/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

import org.cobblestonemc.api.TraversalState;

/**
 * Walk a {@link VirtualPath} to a transition's origin region and traverse that transition, or to
 * the goal itself. The edge cost is the virtual path's cost plus the target's {@link
 * Tier1Node#cost() cost} (the transition's, or nothing for the {@link Tier1Node.Sink sink}).
 *
 * @param <T> the payload type
 * @param <D> the domain type
 * @param virtualPath the same-domain hop to the target
 * @param target the node at the end of the hop: an {@link Tier1Node.AtTransition} for the
 *     transition traversed there, or the {@link Tier1Node.Sink}
 * @param sourceState the accumulated state at the edge's source node
 */
record Tier1Edge<T, D extends Domain>(
    VirtualPath<T, D> virtualPath, Tier1Node<T, D> target, TraversalState sourceState) {}
