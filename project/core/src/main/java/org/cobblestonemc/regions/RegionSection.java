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

/**
 * One section of terrain, built: which region every cell belongs to, the regions, the portals that
 * join them inside the section, and the ledges whose falls leave it through its floor.
 *
 * <p><b>Immutable, and agent-independent.</b> A section says what the terrain is and nothing about
 * who crosses it, so once built it is right for every search over it until a block it was read from
 * changes. That is what lets a {@link RegionStore} keep it in memory, share it between concurrent
 * searches, and write it to disk to survive a restart.
 *
 * <p><b>Region and portal numbers are section-local.</b> A {@link RegionGraph} places a section at
 * an offset of its own when a search first needs it. Portals between sections — seams — are not
 * here: they need both sections, and are cheap enough to compute per search. Nor are falls that
 * leave the section, which need the sections below; the section keeps only where they start.
 *
 * <p><b>Most sections are one region from edge to edge</b> — open sky, solid rock — and keep a
 * single number instead of a cell map, which is most of what keeps a domain's sections small. The
 * rest map each cell to its region in a byte, or in a short for the rare section with more than 255
 * regions, so no section can run out of numbers.
 */
public final class RegionSection {

  /** No region, as a section-local number. */
  static final int NONE = -1;

  /** A byte cell map's "no region". */
  static final int NARROW_NONE = 0xFF;

  /** A short cell map's "no region". */
  static final int WIDE_NONE = 0xFFFF;

  /** A section that is one region, or none, as written. */
  private static final int UNIFORM = 0;

  /** A section with a byte per cell, as written. */
  private static final int NARROW = 1;

  /** A section with a short per cell, as written. */
  private static final int WIDE = 2;

  /** How many bits of a key each of X and Z takes; Y takes the rest. */
  private static final int KEY_BITS = 24;

  /** The four horizontal directions an exit can leave a ledge by, by number. */
  static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  final int sectionX;
  final int sectionY;
  final int sectionZ;
  final int shift;

  /** The region filling the section, {@link #NONE}, or ignored where it has a cell map. */
  final int uniform;

  /** Each cell's region, {@link #NARROW_NONE} for none; or null. */
  final byte[] cells;

  /** As {@link #cells}, for a section of more than 255 regions; or null. */
  final short[] wideCells;

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

  /**
   * Ledges whose falls leave through the section's floor: {@code (cell << 2) | direction}, the
   * standing cell and which of {@link #DIRECTIONS} the open cell it steps off into lies in.
   */
  final int[] exits;

  RegionSection(
      int sectionX,
      int sectionY,
      int sectionZ,
      int shift,
      int uniform,
      byte[] cells,
      short[] wideCells,
      byte[] regionClass,
      int[] regionBox,
      int[] portalA,
      int[] portalB,
      byte[] portalFall,
      int[] portalBox,
      int[] exits) {
    this.sectionX = sectionX;
    this.sectionY = sectionY;
    this.sectionZ = sectionZ;
    this.shift = shift;
    this.uniform = uniform;
    this.cells = cells;
    this.wideCells = wideCells;
    this.regionClass = regionClass;
    this.regionBox = regionBox;
    this.portalA = portalA;
    this.portalB = portalB;
    this.portalFall = portalFall;
    this.portalBox = portalBox;
    this.exits = exits;
  }

  /**
   * Returns the number a section is kept under, by its coordinates.
   *
   * @param sectionX the section X, within ±2²³
   * @param sectionY the section Y, within ±2¹⁵
   * @param sectionZ the section Z, within ±2²³
   * @return the key
   * @throws IllegalArgumentException if a coordinate is out of range: two sections would share a
   *     key
   */
  public static long key(int sectionX, int sectionY, int sectionZ) {
    int yBits = 64 - 2 * KEY_BITS;
    if (!fits(sectionX, KEY_BITS) || !fits(sectionY, yBits) || !fits(sectionZ, KEY_BITS)) {
      throw new IllegalArgumentException(
          "section " + sectionX + ", " + sectionY + ", " + sectionZ + " is too far out to key");
    }
    long mask = (1L << KEY_BITS) - 1;
    return ((sectionX & mask) << (KEY_BITS + yBits))
        | ((sectionY & ((1L << yBits) - 1)) << KEY_BITS)
        | (sectionZ & mask);
  }

  private static boolean fits(int value, int bits) {
    return value >= -(1 << (bits - 1)) && value < (1 << (bits - 1));
  }

  /** Returns a key's section X. */
  static int keyX(long key) {
    return (int) (key >> (64 - KEY_BITS));
  }

  /** Returns a key's section Y. */
  static int keyY(long key) {
    return (int) (key << KEY_BITS >> (2 * KEY_BITS));
  }

  /** Returns a key's section Z. */
  static int keyZ(long key) {
    return (int) (key << (64 - KEY_BITS) >> (64 - KEY_BITS));
  }

  /**
   * Returns a section with no regions at all: one outside the domain, or whose terrain cannot be
   * read.
   *
   * @param spec the domain's spec
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @return the empty section
   */
  static RegionSection empty(RegionSpec<?, ?> spec, int sectionX, int sectionY, int sectionZ) {
    return new RegionSection(
        sectionX,
        sectionY,
        sectionZ,
        spec.shift(),
        NONE,
        null,
        null,
        new byte[0],
        new int[0],
        new int[0],
        new int[0],
        new byte[0],
        new int[0],
        new int[0]);
  }

  /**
   * Returns a section that is one region of a class edge to edge, or none at all, as {@link
   * #wholeCode} describes it.
   *
   * @param spec the domain's spec
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @param code {@code -1} for none, or {@code -2 - class} for one region of that class
   * @return the section
   */
  static RegionSection whole(
      RegionSpec<?, ?> spec, int sectionX, int sectionY, int sectionZ, int code) {
    if (code == -1) {
      return empty(spec, sectionX, sectionY, sectionZ);
    }
    int ox = sectionX << spec.shift();
    int oy = sectionY << spec.shift();
    int oz = sectionZ << spec.shift();
    int far = spec.width() - 1;
    return new RegionSection(
        sectionX,
        sectionY,
        sectionZ,
        spec.shift(),
        0,
        null,
        null,
        new byte[] {(byte) (-2 - code)},
        new int[] {ox, oy, oz, ox + far, oy + far, oz + far},
        new int[0],
        new int[0],
        new byte[0],
        new int[0],
        new int[0]);
  }

  /**
   * Returns what a section that is one class edge to edge, with nothing inside it to join, comes
   * down to: {@code -1} for no region at all, {@code -2 - class} for one region of that class; or
   * {@code 0} for a section that is more than that.
   *
   * @return the code, negative for a whole section
   */
  int wholeCode() {
    if (!uniform() || portalA.length > 0 || exits.length > 0) {
      return 0;
    }
    if (uniform == NONE) {
      return regionClass.length == 0 ? -1 : 0;
    }
    return regionClass.length == 1 ? -2 - regionClass[0] : 0;
  }

  /**
   * Returns the section-local region of a cell, or {@link #NONE}.
   *
   * @param lx the X within the section
   * @param ly the Y within the section
   * @param lz the Z within the section
   * @return the region
   */
  int regionAt(int lx, int ly, int lz) {
    return regionAt((ly << (2 * shift)) | (lz << shift) | lx);
  }

  /**
   * Returns the section-local region of a cell by its number in the section, or {@link #NONE}.
   *
   * @param cell the cell's number
   * @return the region
   */
  int regionAt(int cell) {
    if (cells != null) {
      int value = cells[cell] & 0xFF;
      return value == NARROW_NONE ? NONE : value;
    }
    if (wideCells != null) {
      int value = wideCells[cell] & 0xFFFF;
      return value == WIDE_NONE ? NONE : value;
    }
    return uniform;
  }

  /**
   * Returns whether every cell is in one region, or none.
   *
   * @return {@code true} if the section has no cell map
   */
  boolean uniform() {
    return cells == null && wideCells == null;
  }

  /**
   * Returns how many regions the section holds.
   *
   * @return the region count
   */
  public int regions() {
    return regionClass.length;
  }

  /**
   * Returns how many portals join regions inside the section.
   *
   * @return the portal count
   */
  public int portals() {
    return portalA.length;
  }

  /**
   * Returns roughly how many bytes the section holds in memory.
   *
   * @return the approximate footprint
   */
  public long footprintBytes() {
    long bytes = 96;
    bytes += cells == null ? 0 : 16 + cells.length;
    bytes += wideCells == null ? 0 : 16 + wideCells.length * 2L;
    bytes += regionClass.length * (1L + 24);
    bytes += portalA.length * (4L + 4 + 1 + 24);
    bytes += exits.length * 4L;
    return bytes;
  }

  /**
   * Writes the section.
   *
   * <p>Boxes are written relative to the section's corner, which keeps them small enough for the
   * compression around this to do well; the format is versioned by the file that holds it.
   *
   * @param out where to write
   * @throws IOException if the write fails
   */
  void write(DataOutput out) throws IOException {
    out.writeInt(sectionX);
    out.writeInt(sectionY);
    out.writeInt(sectionZ);
    if (cells != null) {
      out.writeByte(NARROW);
      out.write(cells);
    } else if (wideCells != null) {
      out.writeByte(WIDE);
      for (short value : wideCells) {
        out.writeShort(value);
      }
    } else {
      out.writeByte(UNIFORM);
      out.writeInt(uniform);
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
      out.writeByte(portalFall[p]);
      writeBox(out, portalBox, p);
    }
    out.writeInt(exits.length);
    for (int exit : exits) {
      out.writeInt(exit);
    }
  }

  /**
   * Reads a section written by {@link #write}.
   *
   * @param in where to read
   * @param spec the domain's spec, which the section must have been built under
   * @return the section
   * @throws IOException if the read fails or the data is malformed
   */
  static RegionSection read(DataInput in, RegionSpec<?, ?> spec) throws IOException {
    int sectionX = in.readInt();
    int sectionY = in.readInt();
    int sectionZ = in.readInt();
    int volume = spec.sectionVolume();
    int uniform = NONE;
    byte[] cells = null;
    short[] wideCells = null;
    int kind = in.readByte();
    switch (kind) {
      case UNIFORM -> uniform = in.readInt();
      case NARROW -> {
        cells = new byte[volume];
        in.readFully(cells);
      }
      case WIDE -> {
        wideCells = new short[volume];
        for (int i = 0; i < volume; i++) {
          wideCells[i] = in.readShort();
        }
      }
      default -> throw new IOException("malformed section: kind " + kind);
    }
    int regions = in.readInt();
    if (regions < 0 || regions > volume || uniform >= regions) {
      throw new IOException("malformed section: " + regions + " regions");
    }
    int shift = spec.shift();
    int ox = sectionX << shift;
    int oy = sectionY << shift;
    int oz = sectionZ << shift;
    byte[] regionClass = new byte[regions];
    int[] regionBox = new int[regions * 6];
    for (int r = 0; r < regions; r++) {
      regionClass[r] = in.readByte();
      if (regionClass[r] < 0 || regionClass[r] >= spec.classCount()) {
        throw new IOException("malformed section: region class " + regionClass[r]);
      }
      readBox(in, regionBox, r, ox, oy, oz);
    }
    int portals = in.readInt();
    if (portals < 0) {
      throw new IOException("malformed section: " + portals + " portals");
    }
    int[] portalA = new int[portals];
    int[] portalB = new int[portals];
    byte[] portalFall = new byte[portals];
    int[] portalBox = new int[portals * 6];
    for (int p = 0; p < portals; p++) {
      portalA[p] = in.readInt();
      portalB[p] = in.readInt();
      if (portalA[p] < 0 || portalA[p] >= regions || portalB[p] < 0 || portalB[p] >= regions) {
        throw new IOException("malformed section: portal to a region it does not have");
      }
      portalFall[p] = in.readByte();
      readBox(in, portalBox, p, ox, oy, oz);
    }
    int exitCount = in.readInt();
    if (exitCount < 0 || exitCount > volume * DIRECTIONS.length) {
      throw new IOException("malformed section: " + exitCount + " exits");
    }
    int[] exits = new int[exitCount];
    for (int e = 0; e < exitCount; e++) {
      exits[e] = in.readInt();
      if (exits[e] >>> 2 >= volume) {
        throw new IOException("malformed section: an exit from a cell it does not have");
      }
    }
    return new RegionSection(
        sectionX,
        sectionY,
        sectionZ,
        shift,
        uniform,
        cells,
        wideCells,
        regionClass,
        regionBox,
        portalA,
        portalB,
        portalFall,
        portalBox,
        exits);
  }

  private void writeBox(DataOutput out, int[] boxes, int index) throws IOException {
    int ox = sectionX << shift;
    int oy = sectionY << shift;
    int oz = sectionZ << shift;
    int at = index * 6;
    out.writeShort(boxes[at] - ox);
    out.writeShort(boxes[at + 1] - oy);
    out.writeShort(boxes[at + 2] - oz);
    out.writeShort(boxes[at + 3] - ox);
    out.writeShort(boxes[at + 4] - oy);
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
