/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

/**
 * Constants of the {@code .sbc} capture format — one file per chunk column, holding only the 16³
 * cubes that were actually captured.
 *
 * <p>The format exists because a benchmark corpus lives in version control, which constrains it in
 * ways a runtime format is not: files must be small, <b>byte-stable</b> across re-captures, and
 * diff-localized so that building a test hut in the world changes a handful of files rather than a
 * monolith.
 *
 * <p><b>Payloads are uncompressed on purpose.</b> {@code Deflater} output depends on the JDK's zlib
 * and its level and strategy defaults, so a capture taken on one JDK and re-taken on another would
 * differ byte-for-byte while describing identical blocks, and every re-capture would show up as a
 * diff. Raw payloads are a pure function of the world, so re-capturing unchanged terrain is a
 * genuine no-op. Git compresses the blob anyway, and it can delta-compress similar columns and
 * deduplicate identical ones — which raw bytes help with and pre-compressed bytes defeat.
 */
public final class Sbc {

  /** Magic bytes at the head of every column file: {@code SBC1}. */
  public static final int MAGIC = 0x53_42_43_31;

  /** The format version this codec reads and writes. */
  public static final byte VERSION = 1;

  /** Edge length of a cube, in blocks. One cube is one Minecraft chunk section. */
  public static final int CUBE_SIZE = 16;

  /** Blocks per cube. */
  public static final int CUBE_VOLUME = CUBE_SIZE * CUBE_SIZE * CUBE_SIZE;

  /** The most cubes a column can hold, bounded by {@code sectionMask} being a {@code long}. */
  public static final int MAX_LAYERS = Long.SIZE;

  /** A cube of a single block state, stored as one palette index. */
  public static final byte KIND_UNIFORM = 0;

  /** A cube stored as a bit-packed index array over a cube-local index table. */
  public static final byte KIND_PACKED = 1;

  /**
   * The block state every capture's palette holds at index 0.
   *
   * <p>Reserved so that an all-air cube is representable without the writer having to have seen air
   * anywhere, and so that readers can answer for air without a string comparison.
   */
  public static final String AIR = "minecraft:air";

  /** Palette index of {@link #AIR}. */
  public static final int AIR_INDEX = 0;

  private Sbc() {}

  /**
   * Returns the index within a cube's block array for the given cube-local coordinates.
   *
   * <p>Y-major, then Z, then X — the same ordering Minecraft's own section arrays use, so a copier
   * walking a chunk section in its native order writes sequentially.
   *
   * @param x 0–15
   * @param y 0–15
   * @param z 0–15
   * @return the index, 0–4095
   */
  public static int blockIndex(int x, int y, int z) {
    return (y << 8) | (z << 4) | x;
  }

  /**
   * Returns the section index containing the given world Y — the world Y divided by 16, rounded
   * towards negative infinity.
   *
   * <p>Named rather than inlined because the two coordinate spaces look alike and read alike: a
   * section index of {@code -4} and a world Y of {@code -4} are both small negative numbers, and
   * passing one where the other belongs produces a column that decodes cleanly and describes the
   * wrong part of the world.
   *
   * @param worldY the world Y
   * @return the section index
   */
  public static int sectionOf(int worldY) {
    return Math.floorDiv(worldY, CUBE_SIZE);
  }

  /**
   * Returns the lowest world Y contained by the given section.
   *
   * @param sectionY the section index
   * @return the world Y of the section's floor
   */
  public static int sectionFloor(int sectionY) {
    return sectionY * CUBE_SIZE;
  }

  /**
   * Returns the number of bits needed to store indices {@code 0..count-1}, at least one.
   *
   * <p>At least one because a cube with a single distinct state is written as {@link
   * #KIND_UNIFORM}, so a packed cube always has two or more — but a zero-bit encoding would be a
   * silent division by zero rather than an error, and one bit costs 512 bytes to be safe.
   *
   * @param count the number of distinct values
   * @return the bits per entry, 1–16
   */
  public static int bitsPerEntry(int count) {
    return Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, count - 1)));
  }

  /**
   * Returns how many entries fit in one {@code long} at the given width.
   *
   * <p>Entries never straddle a {@code long} boundary, matching Anvil since 1.16. Straddling would
   * save a few percent and cost every read a second word fetch and a shift-and-merge; a capture is
   * read tens of millions of times per benchmark and written once.
   *
   * @param bitsPerEntry the entry width in bits
   * @return the entries per long
   */
  public static int entriesPerLong(int bitsPerEntry) {
    return Long.SIZE / bitsPerEntry;
  }

  /**
   * Returns the length of the backing {@code long} array for a full cube at the given width.
   *
   * @param bitsPerEntry the entry width in bits
   * @return the array length
   */
  public static int packedLength(int bitsPerEntry) {
    int perLong = entriesPerLong(bitsPerEntry);
    return (CUBE_VOLUME + perLong - 1) / perLong;
  }
}
