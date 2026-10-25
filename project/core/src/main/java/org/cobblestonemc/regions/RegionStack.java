/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.util.Arrays;

/**
 * One stack of terrain, built: which region every cell belongs to, the regions, and the portals
 * that join them inside the stack.
 *
 * <p><b>Immutable, and agent-independent.</b> A stack says what the terrain is and nothing about
 * who crosses it, so once built it is right for every search over that stack until a block in it
 * changes. That is what lets a {@link RegionStore} keep it in memory and share it between
 * concurrent searches.
 *
 * <p><b>Region and portal numbers are stack-local.</b> A {@link RegionGraph} places a stack at an
 * offset of its own when a search first needs it. Portals between stacks — seams — are not here:
 * they need both stacks, and are cheap enough to compute per search.
 *
 * <p><b>Most sections are one region from edge to edge</b> — open sky, solid rock — and keep a
 * single number instead of a cell map, which is most of what keeps a stack small. The rest map each
 * cell to its region in a byte, or in a short for the rare section with more than 255 regions, so
 * no section can run out of numbers.
 */
public final class RegionStack {

  /** No region, as a stack-local number. */
  static final int NONE = -1;

  /** A byte cell map's "no region". */
  static final int NARROW_NONE = 0xFF;

  /** A short cell map's "no region". */
  static final int WIDE_NONE = 0xFFFF;

  final int stackX;
  final int stackZ;
  final int minY;
  final int shift;

  /** Per section: the region filling it, {@link #NONE}, or ignored where it has a cell map. */
  final int[] uniform;

  /** Per section: the first region number its cell map counts from. */
  final int[] base;

  /** Per section: each cell's region less {@link #base}, {@link #NARROW_NONE} for none; or null. */
  final byte[][] cells;

  /** Per section: as {@link #cells}, for a section of more than 255 regions; or null. */
  final short[][] wideCells;

  final byte[] regionClass;

  /** Six ints per region: minimum X, Y, Z and maximum X, Y, Z, in domain coordinates. */
  final int[] regionBox;

  final int[] portalA;
  final int[] portalB;

  /**
   * Per portal: how far a body falls through it, from A to B only, or 0 if it is crossed both ways.
   */
  final byte[] portalFall;

  /** Six ints per portal, as {@link #regionBox}. */
  final int[] portalBox;

  RegionStack(
      int stackX,
      int stackZ,
      int minY,
      int shift,
      int[] uniform,
      int[] base,
      byte[][] cells,
      short[][] wideCells,
      byte[] regionClass,
      int[] regionBox,
      int[] portalA,
      int[] portalB,
      byte[] portalFall,
      int[] portalBox) {
    this.stackX = stackX;
    this.stackZ = stackZ;
    this.minY = minY;
    this.shift = shift;
    this.uniform = uniform;
    this.base = base;
    this.cells = cells;
    this.wideCells = wideCells;
    this.regionClass = regionClass;
    this.regionBox = regionBox;
    this.portalA = portalA;
    this.portalB = portalB;
    this.portalFall = portalFall;
    this.portalBox = portalBox;
  }

  /**
   * Returns the number a stack is kept under, by its coordinates.
   *
   * @param stackX the stack X
   * @param stackZ the stack Z
   * @return the key
   */
  public static long key(int stackX, int stackZ) {
    return ((long) stackX << 32) | (stackZ & 0xFFFF_FFFFL);
  }

  /**
   * Returns a stack with no regions at all: one whose terrain cannot be read.
   *
   * @param spec the domain's spec
   * @param stackX the stack X
   * @param stackZ the stack Z
   * @return the empty stack
   */
  static RegionStack empty(RegionSpec<?> spec, int stackX, int stackZ) {
    int sections = spec.sections();
    int[] uniform = new int[sections];
    Arrays.fill(uniform, NONE);
    return new RegionStack(
        stackX,
        stackZ,
        spec.stackMinY(),
        spec.shift(),
        uniform,
        new int[sections],
        new byte[sections][],
        new short[sections][],
        new byte[0],
        new int[0],
        new int[0],
        new int[0],
        new byte[0],
        new int[0]);
  }

  /**
   * Returns the stack-local region of a cell, or {@link #NONE}.
   *
   * @param lx the X within the stack
   * @param ly the Y counted from {@link #minY}
   * @param lz the Z within the stack
   * @return the region
   */
  int regionAt(int lx, int ly, int lz) {
    int section = ly >> shift;
    if (ly < 0 || section >= uniform.length) {
      return NONE;
    }
    int mask = (1 << shift) - 1;
    int cell = ((ly & mask) << (2 * shift)) | (lz << shift) | lx;
    byte[] narrow = cells[section];
    if (narrow != null) {
      int value = narrow[cell] & 0xFF;
      return value == NARROW_NONE ? NONE : base[section] + value;
    }
    short[] wide = wideCells[section];
    if (wide != null) {
      int value = wide[cell] & 0xFFFF;
      return value == WIDE_NONE ? NONE : base[section] + value;
    }
    return uniform[section];
  }

  /**
   * Returns how many regions the stack holds.
   *
   * @return the region count
   */
  public int regions() {
    return regionClass.length;
  }

  /**
   * Returns how many portals join regions inside the stack.
   *
   * @return the portal count
   */
  public int portals() {
    return portalA.length;
  }

  /**
   * Returns roughly how many bytes the stack holds in memory.
   *
   * @return the approximate footprint
   */
  public long footprintBytes() {
    long bytes = 112 + uniform.length * 24L;
    for (byte[] narrow : cells) {
      bytes += narrow == null ? 0 : 16 + narrow.length;
    }
    for (short[] wide : wideCells) {
      bytes += wide == null ? 0 : 16 + wide.length * 2L;
    }
    bytes += regionClass.length * (1L + 24);
    bytes += portalA.length * (4L + 4 + 1 + 24);
    return bytes;
  }
}
