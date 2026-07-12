/*
 * Odyssey — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package net.whimxiqal.odyssey.core;

import net.whimxiqal.odyssey.api.Cell;
import net.whimxiqal.odyssey.api.Domain;
import net.whimxiqal.odyssey.api.DomainRegion;

/**
 * A {@link DomainRegion} consisting of exactly one cell — the common case for a precise destination
 * or a single-block transition endpoint.
 *
 * @param <D> the domain type
 * @param cell the single cell of the region
 * @param domain the domain the cell lives in
 */
public record CellRegion<D extends Domain>(Cell cell, D domain) implements DomainRegion<D> {

  @Override
  public boolean contains(Cell other) {
    return cell.equals(other);
  }

  @Override
  public Cell nearestBoundaryCell(Cell from) {
    return cell;
  }
}
