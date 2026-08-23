/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc;

/**
 * An immutable located point: a {@link Cell} within a concrete {@link Domain} instance.
 *
 * @param <D> the domain type
 * @param cell the cell
 * @param domain the domain the cell lives in
 */
public record Position<D extends Domain>(Cell cell, D domain) {}
