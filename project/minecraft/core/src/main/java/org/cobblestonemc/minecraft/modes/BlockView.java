/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.modes;

import java.util.Collection;
import org.cobblestonemc.Cell;
import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.UnknownBlock;

/**
 * A read-only snapshot of the blocks a mode fetched for one expansion, over a box of cells. Cells
 * outside the box, and cells inside it that were never fetched, read as {@link
 * UnknownBlock#INSTANCE}.
 *
 * <p>Backed by a dense array rather than a map. Modes and {@link Geometry} read this a few hundred
 * times per expansion — {@code bodyFits} alone is two reads, and every mode calls it per direction
 * — so a map cost a hash of a {@link Cell} record on each read, and the relative form {@link
 * #at(Cell, int, int, int)} allocated a fresh {@code Cell} for the key. Here both are index
 * arithmetic on ints, and nothing is allocated.
 */
final class BlockView {

  private final int minX;
  private final int minY;
  private final int minZ;
  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final MinecraftBlock[] blocks;

  private BlockView(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    this.minX = minX;
    this.minY = minY;
    this.minZ = minZ;
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.blocks = new MinecraftBlock[sizeX * sizeY * sizeZ];
  }

  /** A view holding nothing; every read is unknown. */
  static BlockView empty() {
    return new BlockView(0, 0, 0, 0, 0, 0);
  }

  /**
   * A view over the box {@code xzRadius} out and {@code [dyLow, dyHigh]} up from {@code center}.
   */
  static BlockView around(Cell center, int xzRadius, int dyLow, int dyHigh) {
    return new BlockView(
        center.x() - xzRadius,
        center.y() + dyLow,
        center.z() - xzRadius,
        xzRadius * 2 + 1,
        dyHigh - dyLow + 1,
        xzRadius * 2 + 1);
  }

  /** A view over the smallest box containing every given cell. */
  static BlockView bounding(Collection<Cell> cells) {
    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (Cell cell : cells) {
      minX = Math.min(minX, cell.x());
      minY = Math.min(minY, cell.y());
      minZ = Math.min(minZ, cell.z());
      maxX = Math.max(maxX, cell.x());
      maxY = Math.max(maxY, cell.y());
      maxZ = Math.max(maxZ, cell.z());
    }
    return new BlockView(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
  }

  MinecraftBlock at(Cell cell) {
    return at(cell.x(), cell.y(), cell.z());
  }

  MinecraftBlock at(Cell cell, int dx, int dy, int dz) {
    return at(cell.x() + dx, cell.y() + dy, cell.z() + dz);
  }

  private MinecraftBlock at(int x, int y, int z) {
    int index = indexOf(x, y, z);
    if (index < 0) {
      return UnknownBlock.INSTANCE;
    }
    MinecraftBlock block = blocks[index];
    return block == null ? UnknownBlock.INSTANCE : block;
  }

  /** Whether this cell is inside the box <em>and</em> has already been filled. */
  boolean holds(Cell cell) {
    int index = indexOf(cell.x(), cell.y(), cell.z());
    return index >= 0 && blocks[index] != null;
  }

  /** Whether this cell is inside the box at all. */
  boolean covers(Cell cell) {
    return indexOf(cell.x(), cell.y(), cell.z()) >= 0;
  }

  /** Stores a fetched block. Cells outside the box are dropped — they can only read as unknown. */
  void put(Cell cell, MinecraftBlock block) {
    int index = indexOf(cell.x(), cell.y(), cell.z());
    if (index >= 0) {
      blocks[index] = block;
    }
  }

  /** The array index for a world position, or {@code -1} if it lies outside the box. */
  private int indexOf(int x, int y, int z) {
    int ix = x - minX;
    int iy = y - minY;
    int iz = z - minZ;
    if (ix < 0 || ix >= sizeX || iy < 0 || iy >= sizeY || iz < 0 || iz >= sizeZ) {
      return -1;
    }
    return (ix * sizeY + iy) * sizeZ + iz;
  }
}
