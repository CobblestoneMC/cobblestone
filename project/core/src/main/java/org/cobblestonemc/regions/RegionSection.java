/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

/**
 * One section of terrain, built: which region every cell belongs to, the regions, the portals that
 * join them inside the section, and the ledges whose falls leave it through its floor.
 *
 * <p><b>Immutable, and agent-independent.</b> A section says what the terrain is and nothing about
 * who crosses it, so once built it is right for every search over it until a block it was read from
 * changes. That is what lets a {@link RegionStore} keep it in memory and share it between
 * concurrent searches.
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
}
