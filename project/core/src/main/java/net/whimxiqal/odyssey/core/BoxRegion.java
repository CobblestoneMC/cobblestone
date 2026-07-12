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
 * An axis-aligned box of cells within one world — the target region for
 * {@code navigatePlayerToRegion}. The nearest boundary cell is the component-wise clamp of a query
 * cell into the box, so the heuristic measures the true nearest entry point.
 */
public final class BoxRegion<D extends Domain> implements DomainRegion<D> {

  private final D domain;
  private final int minX;
  private final int minY;
  private final int minZ;
  private final int maxX;
  private final int maxY;
  private final int maxZ;

  public BoxRegion(D domain, Cell corner1, Cell corner2) {
    this.domain = domain;
    this.minX = Math.min(corner1.x(), corner2.x());
    this.minY = Math.min(corner1.y(), corner2.y());
    this.minZ = Math.min(corner1.z(), corner2.z());
    this.maxX = Math.max(corner1.x(), corner2.x());
    this.maxY = Math.max(corner1.y(), corner2.y());
    this.maxZ = Math.max(corner1.z(), corner2.z());
  }

  @Override
  public D domain() {
    return domain;
  }

  @Override
  public boolean contains(Cell cell) {
    return cell.x() >= minX && cell.x() <= maxX
        && cell.y() >= minY && cell.y() <= maxY
        && cell.z() >= minZ && cell.z() <= maxZ;
  }

  @Override
  public Cell nearestBoundaryCell(Cell from) {
    if (contains(from)) {
      return from;
    }
    int x = Math.clamp(from.x(), minX, maxX);
    int y = Math.clamp(from.y(), minY, maxY);
    int z = Math.clamp(from.z(), minZ, maxZ);
    return new Cell(x, y, z);
  }
}
