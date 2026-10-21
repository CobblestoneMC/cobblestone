/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * One column of terrain, built: which region every cell belongs to, the regions, and the portals
 * that join them inside the column.
 *
 * <p><b>Immutable, and agent-independent.</b> A column says what the terrain is and nothing about
 * who crosses it, so once built it is right for every search over that column until a block in it
 * changes. That is what lets a {@link RegionStore} keep it in memory, share it between concurrent
 * searches, and write it to disk to survive a restart.
 *
 * <p><b>Region and portal numbers are column-local.</b> A {@link RegionGraph} places a column at an
 * offset of its own when a search first needs it. Portals between columns — seams — are not here:
 * they need both columns, and are cheap enough to compute per search.
 *
 * <p><b>Most sections are one region from edge to edge</b> — open sky, solid rock — and keep a
 * single number instead of a cell map, which is most of what keeps a column small. The rest map
 * each cell to its region in a byte, or in a short for the rare section with more than 255 regions,
 * so no section can run out of numbers.
 */
public final class RegionColumn {

  /** No region, as a column-local number. */
  static final int NONE = -1;

  /** A byte cell map's "no region". */
  static final int NARROW_NONE = 0xFF;

  /** A short cell map's "no region". */
  static final int WIDE_NONE = 0xFFFF;

  final int columnX;
  final int columnZ;
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

  /** Six ints per region: minimum X, Y, Z and maximum X, Y, Z, in world coordinates. */
  final int[] regionBox;

  final int[] portalA;
  final int[] portalB;
  final boolean[] portalDirected;

  /** Six ints per portal, as {@link #regionBox}. */
  final int[] portalBox;

  RegionColumn(
      int columnX,
      int columnZ,
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
      boolean[] portalDirected,
      int[] portalBox) {
    this.columnX = columnX;
    this.columnZ = columnZ;
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
    this.portalDirected = portalDirected;
    this.portalBox = portalBox;
  }

  /**
   * Returns a column with no regions at all: one whose terrain cannot be read.
   *
   * @param spec the world's spec
   * @param columnX the column X
   * @param columnZ the column Z
   * @return the empty column
   */
  static RegionColumn empty(RegionSpec<?> spec, int columnX, int columnZ) {
    int sections = spec.sections();
    int[] uniform = new int[sections];
    Arrays.fill(uniform, NONE);
    return new RegionColumn(
        columnX,
        columnZ,
        spec.columnMinY(),
        spec.shift(),
        uniform,
        new int[sections],
        new byte[sections][],
        new short[sections][],
        new byte[0],
        new int[0],
        new int[0],
        new int[0],
        new boolean[0],
        new int[0]);
  }

  /**
   * Returns the column-local region of a cell, or {@link #NONE}.
   *
   * @param lx the X within the column
   * @param ly the Y counted from {@link #minY}
   * @param lz the Z within the column
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
   * Returns how many regions the column holds.
   *
   * @return the region count
   */
  public int regions() {
    return regionClass.length;
  }

  /**
   * Returns how many portals join regions inside the column.
   *
   * @return the portal count
   */
  public int portals() {
    return portalA.length;
  }

  /**
   * Returns roughly how many bytes the column holds in memory.
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

  /** A section that is one region, or none. */
  private static final int UNIFORM = 0;

  /** A section with a byte per cell. */
  private static final int NARROW = 1;

  /** A section with a short per cell. */
  private static final int WIDE = 2;

  /**
   * Writes the column.
   *
   * <p>Boxes are written relative to the column's corner, which keeps them small enough for the
   * compression around this to do well; the format is versioned by the file that holds it.
   *
   * @param out where to write
   * @throws IOException if the write fails
   */
  void write(DataOutput out) throws IOException {
    out.writeInt(columnX);
    out.writeInt(columnZ);
    out.writeShort(uniform.length);
    for (int s = 0; s < uniform.length; s++) {
      if (cells[s] != null) {
        out.writeByte(NARROW);
        out.writeInt(base[s]);
        out.write(cells[s]);
      } else if (wideCells[s] != null) {
        out.writeByte(WIDE);
        out.writeInt(base[s]);
        for (short value : wideCells[s]) {
          out.writeShort(value);
        }
      } else {
        out.writeByte(UNIFORM);
        out.writeInt(uniform[s]);
      }
    }
    out.writeInt(regionClass.length);
    for (int r = 0; r < regionClass.length; r++) {
      out.writeByte(regionClass[r]);
      writeBox(out, regionBox, r);
    }
    out.writeInt(portalA.length);
    for (int p = 0; p < portalA.length; p++) {
      out.writeInt(portalA[p]);
      out.writeInt(portalB[p]);
      out.writeBoolean(portalDirected[p]);
      writeBox(out, portalBox, p);
    }
  }

  /**
   * Reads a column written by {@link #write}.
   *
   * @param in where to read
   * @param spec the world's spec, which the column must have been built under
   * @return the column
   * @throws IOException if the read fails or the data is malformed
   */
  static RegionColumn read(DataInput in, RegionSpec<?> spec) throws IOException {
    int columnX = in.readInt();
    int columnZ = in.readInt();
    int sections = in.readUnsignedShort();
    if (sections != spec.sections()) {
      throw new IOException("malformed column: " + sections + " sections");
    }
    int volume = spec.sectionVolume();
    int[] uniform = new int[sections];
    int[] base = new int[sections];
    byte[][] cells = new byte[sections][];
    short[][] wideCells = new short[sections][];
    for (int s = 0; s < sections; s++) {
      int kind = in.readByte();
      switch (kind) {
        case UNIFORM -> uniform[s] = in.readInt();
        case NARROW -> {
          base[s] = in.readInt();
          cells[s] = new byte[volume];
          in.readFully(cells[s]);
        }
        case WIDE -> {
          base[s] = in.readInt();
          wideCells[s] = new short[volume];
          for (int i = 0; i < volume; i++) {
            wideCells[s][i] = in.readShort();
          }
        }
        default -> throw new IOException("malformed column: section kind " + kind);
      }
    }
    int regions = in.readInt();
    if (regions < 0 || regions > sections * volume) {
      throw new IOException("malformed column: " + regions + " regions");
    }
    int minY = spec.columnMinY();
    int ox = columnX << spec.shift();
    int oz = columnZ << spec.shift();
    int classes = spec.terrain().classes();
    byte[] regionClass = new byte[regions];
    int[] regionBox = new int[regions * 6];
    for (int r = 0; r < regions; r++) {
      regionClass[r] = in.readByte();
      if (regionClass[r] < 0 || regionClass[r] >= classes) {
        throw new IOException("malformed column: region class " + regionClass[r]);
      }
      readBox(in, regionBox, r, ox, minY, oz);
    }
    int portals = in.readInt();
    if (portals < 0) {
      throw new IOException("malformed column: " + portals + " portals");
    }
    int[] portalA = new int[portals];
    int[] portalB = new int[portals];
    boolean[] portalDirected = new boolean[portals];
    int[] portalBox = new int[portals * 6];
    for (int p = 0; p < portals; p++) {
      portalA[p] = in.readInt();
      portalB[p] = in.readInt();
      if (portalA[p] < 0 || portalA[p] >= regions || portalB[p] < 0 || portalB[p] >= regions) {
        throw new IOException("malformed column: portal to a region it does not have");
      }
      portalDirected[p] = in.readBoolean();
      readBox(in, portalBox, p, ox, minY, oz);
    }
    return new RegionColumn(
        columnX,
        columnZ,
        minY,
        spec.shift(),
        uniform,
        base,
        cells,
        wideCells,
        regionClass,
        regionBox,
        portalA,
        portalB,
        portalDirected,
        portalBox);
  }

  private void writeBox(DataOutput out, int[] boxes, int index) throws IOException {
    int ox = columnX << shift;
    int oz = columnZ << shift;
    int at = index * 6;
    // A box can reach one block past its column on either side, so relative values fit a short.
    out.writeShort(boxes[at] - ox);
    out.writeShort(boxes[at + 1] - minY);
    out.writeShort(boxes[at + 2] - oz);
    out.writeShort(boxes[at + 3] - ox);
    out.writeShort(boxes[at + 4] - minY);
    out.writeShort(boxes[at + 5] - oz);
  }

  private static void readBox(DataInput in, int[] boxes, int index, int ox, int oy, int oz)
      throws IOException {
    int at = index * 6;
    boxes[at] = ox + in.readShort();
    boxes[at + 1] = oy + in.readShort();
    boxes[at + 2] = oz + in.readShort();
    boxes[at + 3] = ox + in.readShort();
    boxes[at + 4] = oy + in.readShort();
    boxes[at + 5] = oz + in.readShort();
  }
}
