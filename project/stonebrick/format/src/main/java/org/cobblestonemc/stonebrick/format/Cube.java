/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

/**
 * One captured 16³ cube — a Minecraft chunk section — as palette indices.
 *
 * <p>Immutable and safe to read from any thread. {@link #paletteIndex} is the hot path of every
 * benchmark, read tens of millions of times per solve, so implementations must answer it without
 * allocating and without a branch on anything but their own shape.
 *
 * <p>A cube that was <i>not captured</i> is not represented here at all; it is absent from its
 * column's section mask, which is a different thing from a cube of air and is treated differently
 * (see {@link ChunkColumn}).
 */
public sealed interface Cube permits Cube.Uniform, Cube.Packed {

  /**
   * Returns the palette index of the block at the given cube-local coordinates.
   *
   * @param x 0–15
   * @param y 0–15
   * @param z 0–15
   * @return the index into the column's {@link BlockPalette}
   */
  int paletteIndex(int x, int y, int z);

  /**
   * Returns whether every block in this cube is the same state.
   *
   * @return {@code true} if uniform
   */
  boolean isUniform();

  /**
   * A cube of a single block state — all stone, all air, all water.
   *
   * <p>The common case by a wide margin on real terrain, and the reason a full-column capture costs
   * so much less than its volume suggests: below the surface most cubes are solid stone and cost
   * three bytes.
   *
   * @param index the palette index every block in the cube has
   */
  record Uniform(int index) implements Cube {

    @Override
    public int paletteIndex(int x, int y, int z) {
      return index;
    }

    @Override
    public boolean isUniform() {
      return true;
    }
  }

  /**
   * A cube stored as a bit-packed array of indices into a cube-local table.
   *
   * <p><b>Two levels of indirection on purpose.</b> The packed entries index {@code localIds}, and
   * {@code localIds} indexes the column palette. A column may hold sixty distinct states while any
   * one cube in it holds three, and it is the cube's own count that sets the entry width — so a
   * stone-and-air cube costs one bit per block rather than the six its column would otherwise
   * impose. On mixed terrain this is the difference between a 3 KB cube and a 512-byte one.
   */
  final class Packed implements Cube {

    private final int[] localIds;
    private final long[] data;
    private final int bitsPerEntry;
    private final int entriesPerLong;
    private final long mask;

    /**
     * Creates a packed cube. The arrays are taken by reference and must not be mutated afterwards.
     *
     * @param localIds palette indices this cube uses, in local-index order
     * @param data the packed entries
     * @param bitsPerEntry the entry width in bits
     */
    Packed(int[] localIds, long[] data, int bitsPerEntry) {
      this.localIds = localIds;
      this.data = data;
      this.bitsPerEntry = bitsPerEntry;
      this.entriesPerLong = Sbc.entriesPerLong(bitsPerEntry);
      this.mask = (1L << bitsPerEntry) - 1;
    }

    @Override
    public int paletteIndex(int x, int y, int z) {
      int entry = Sbc.blockIndex(x, y, z);
      int word = entry / entriesPerLong;
      int shift = (entry % entriesPerLong) * bitsPerEntry;
      return localIds[(int) ((data[word] >>> shift) & mask)];
    }

    @Override
    public boolean isUniform() {
      return false;
    }

    /**
     * Returns the palette indices this cube uses, in local-index order.
     *
     * @return the local index table; not to be mutated
     */
    int[] localIds() {
      return localIds;
    }

    /**
     * Returns the packed entry array.
     *
     * @return the packed data; not to be mutated
     */
    long[] data() {
      return data;
    }

    /**
     * Returns the entry width in bits.
     *
     * @return the bits per entry
     */
    int bitsPerEntry() {
      return bitsPerEntry;
    }
  }

  /**
   * Builds the most compact cube representation for a full array of palette indices.
   *
   * <p>Chooses {@link Uniform} when every block agrees, and otherwise packs at the narrowest width
   * the cube's own distinct-state count allows. The choice is a pure function of the input, so two
   * captures of identical blocks produce identical bytes.
   *
   * @param paletteIndices exactly {@link Sbc#CUBE_VOLUME} palette indices, in {@link
   *     Sbc#blockIndex} order
   * @return the cube
   * @throws IllegalArgumentException if the array is the wrong length
   */
  static Cube of(int[] paletteIndices) {
    if (paletteIndices.length != Sbc.CUBE_VOLUME) {
      throw new IllegalArgumentException(
          "expected " + Sbc.CUBE_VOLUME + " indices, got " + paletteIndices.length);
    }
    // First-seen order for the local table, for the same reason the palette uses it: determinism
    // without a sort.
    int[] localOf = new int[paletteIndices.length];
    int[] localIds = new int[Math.min(paletteIndices.length, 1 << 12)];
    int localCount = 0;
    for (int i = 0; i < paletteIndices.length; i++) {
      int index = paletteIndices[i];
      int local = -1;
      for (int j = 0; j < localCount; j++) {
        if (localIds[j] == index) {
          local = j;
          break;
        }
      }
      if (local < 0) {
        if (localCount == localIds.length) {
          int[] grown = new int[localIds.length * 2];
          System.arraycopy(localIds, 0, grown, 0, localCount);
          localIds = grown;
        }
        local = localCount;
        localIds[localCount++] = index;
      }
      localOf[i] = local;
    }
    if (localCount == 1) {
      return new Uniform(localIds[0]);
    }

    int bits = Sbc.bitsPerEntry(localCount);
    int perLong = Sbc.entriesPerLong(bits);
    long[] data = new long[Sbc.packedLength(bits)];
    for (int i = 0; i < localOf.length; i++) {
      int word = i / perLong;
      int shift = (i % perLong) * bits;
      data[word] |= ((long) localOf[i]) << shift;
    }
    int[] table = new int[localCount];
    System.arraycopy(localIds, 0, table, 0, localCount);
    return new Packed(table, data, bits);
  }
}
