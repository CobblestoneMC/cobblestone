/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.api;

/**
 * One step of a solved path: a reached position plus the cost and time attributed to <i>this</i>
 * step (not a running total). Keeping the values per-step lets {@link Path} total them on demand
 * and keeps each step self-describing.
 *
 * <p>{@code cost} is the algorithm-facing metric the search minimizes (it may later fold in danger
 * or other penalties); {@code time} is the player-facing real duration of the step, in seconds.
 * Today the two are equal everywhere, but they are stored separately so danger weighting can
 * diverge cost from time without breaking any duration readout.
 *
 * @param <P> the position type
 * @param <T> the payload type
 * @param position the position reached by this step
 * @param cost the algorithm cost attributed to this step, in seconds
 * @param time the real traversal time of this step, in seconds
 * @param payload the payload carried to the search result
 */
public record Step<P, T>(P position, double cost, double time, T payload) {}
