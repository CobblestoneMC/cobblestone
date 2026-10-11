/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.format;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One captured chunk column: the cubes of a single 16×16 chunk that a capture actually holds.
 *
 * <p><b>A column is self-describing.</b> It carries its own chunk coordinates and the world Y its
 * lowest possible cube sits at, so a file that has been renamed, moved between captures, or picked
 * out of a directory listing still says exactly what it is. That is what lets the capture format go
 * without a manifest: the directory listing is the manifest.
 *
 * <p><b>Partial vertical capture is the normal case.</b> A scenario about walking across the
 * surface has no use for the 300 blocks of stone beneath it, and capturing them would cost six
 * times the disk for nothing. {@link #sectionMask()} records precisely which cubes exist.
 *
 * <p><b>Absent is not air.</b> A cube outside the mask was never captured, and asking about it is a
 * different event from finding air there — it means the benchmark has run off the edge of its data
 * and the run is degenerate. {@link #paletteIndexOrAbsent} reports that distinctly rather than
 * fabricating a block, and the platform decides whether to abort loudly or treat it as a wall.
 */
public final class ChunkColumn {

  /** Returned by {@link #paletteIndexOrAbsent} for a cube this column does not contain. */
  public static final int ABSENT = -1;

  private final int chunkX;
  private final int chunkZ;
  private final int minSectionY;
  private final long sectionMask;
  private final BlockPalette palette;

  /** Indexed by layer (bit position in {@link #sectionMask}); {@code null} where absent. */
  private final Cube[] cubes;

  ChunkColumn(
      int chunkX,
      int chunkZ,
      int minSectionY,
      long sectionMask,
      BlockPalette palette,
      Cube[] cubes) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.minSectionY = minSectionY;
    this.sectionMask = sectionMask;
    this.palette = palette;
    this.cubes = cubes;
  }

  /**
   * Returns the chunk X coordinate.
   *
   * @return the chunk X
   */
  public int chunkX() {
    return chunkX;
  }

  /**
   * Returns the chunk Z coordinate.
   *
   * @return the chunk Z
   */
  public int chunkZ() {
    return chunkZ;
  }

  /**
   * Returns the section index (world Y divided by 16, floored) that bit 0 of the section mask
   * refers to. For a modern overworld this is {@code -4}.
   *
   * @return the lowest section index this column can describe
   */
  public int minSectionY() {
    return minSectionY;
  }

  /**
   * Returns the bit set of captured cubes; bit {@code i} means the cube at section {@code
   * minSectionY() + i} was captured.
   *
   * @return the section mask
   */
  public long sectionMask() {
    return sectionMask;
  }

  /**
   * Returns this column's palette.
   *
   * @return the palette
   */
  public BlockPalette palette() {
    return palette;
  }

  /**
   * Returns the cube covering the given world Y, or {@code null} if it was not captured.
   *
   * @param y the world Y
   * @return the cube, or {@code null}
   */
  public @Nullable Cube cubeAt(int y) {
    int layer = Sbc.sectionOf(y) - minSectionY;
    if (layer < 0 || layer >= Sbc.MAX_LAYERS || (sectionMask & (1L << layer)) == 0) {
      return null;
    }
    return cubes[layer];
  }

  /**
   * Returns the palette index of the block at the given position, or {@link #ABSENT} if that cube
   * was not captured.
   *
   * @param localX 0–15, block X within the chunk
   * @param y the world Y
   * @param localZ 0–15, block Z within the chunk
   * @return the palette index, or {@link #ABSENT}
   */
  public int paletteIndexOrAbsent(int localX, int y, int localZ) {
    Cube cube = cubeAt(y);
    if (cube == null) {
      return ABSENT;
    }
    return cube.paletteIndex(localX, Math.floorMod(y, Sbc.CUBE_SIZE), localZ);
  }

  /**
   * Returns the block state string at the given position, or {@code null} if that cube was not
   * captured.
   *
   * <p>For diagnostics and tooling. A benchmark reads {@link #paletteIndexOrAbsent} and resolves
   * traits through the capture's trait table rather than comparing strings.
   *
   * @param localX 0–15
   * @param y the world Y
   * @param localZ 0–15
   * @return the block state string, or {@code null}
   */
  public @Nullable String blockStateOrNull(int localX, int y, int localZ) {
    int index = paletteIndexOrAbsent(localX, y, localZ);
    return index == ABSENT ? null : palette.state(index);
  }

  /**
   * Returns the number of captured cubes.
   *
   * @return the cube count
   */
  public int cubeCount() {
    return Long.bitCount(sectionMask);
  }

  /**
   * Returns the cube at the given layer, or {@code null} if absent.
   *
   * @param layer the bit position within the section mask
   * @return the cube, or {@code null}
   */
  @Nullable
  Cube cubeAtLayer(int layer) {
    return layer < 0 || layer >= Sbc.MAX_LAYERS ? null : cubes[layer];
  }

  @Override
  public String toString() {
    return "ChunkColumn[" + chunkX + ", " + chunkZ + ", cubes=" + cubeCount() + "]";
  }

  /**
   * Creates a builder for a column at the given chunk coordinates.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param minSectionY the section index bit 0 of the mask refers to
   * @return a new builder
   */
  public static Builder builder(int chunkX, int chunkZ, int minSectionY) {
    return new Builder(chunkX, chunkZ, minSectionY);
  }

  /**
   * Accumulates cubes into a column.
   *
   * <p>Not thread-safe; a copier builds one column at a time on one thread.
   */
  public static final class Builder {

    private final int chunkX;
    private final int chunkZ;
    private final int minSectionY;
    private final BlockPalette.Builder palette = BlockPalette.builder();
    private final Cube[] cubes = new Cube[Sbc.MAX_LAYERS];
    private long sectionMask;

    private Builder(int chunkX, int chunkZ, int minSectionY) {
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.minSectionY = minSectionY;
    }

    /**
     * Adds a captured cube from block state strings.
     *
     * @param sectionY the section index (world Y / 16, floored)
     * @param blockStates exactly {@link Sbc#CUBE_VOLUME} block state strings in {@link
     *     Sbc#blockIndex} order
     * @return this builder
     * @throws IllegalArgumentException if the section is out of range or the array is the wrong
     *     length
     */
    public Builder cubeAtSection(int sectionY, String[] blockStates) {
      Objects.requireNonNull(blockStates, "blockStates");
      if (blockStates.length != Sbc.CUBE_VOLUME) {
        throw new IllegalArgumentException(
            "expected " + Sbc.CUBE_VOLUME + " block states, got " + blockStates.length);
      }
      int[] indices = new int[blockStates.length];
      for (int i = 0; i < blockStates.length; i++) {
        indices[i] = palette.add(blockStates[i]);
      }
      return cubeAtSection(sectionY, Cube.of(indices));
    }

    /**
     * Adds an already-built cube, whose palette indices must belong to this builder's palette.
     *
     * @param sectionY the section index (world Y / 16, floored)
     * @param cube the cube
     * @return this builder
     * @throws IllegalArgumentException if the section is out of this column's range
     */
    public Builder cubeAtSection(int sectionY, Cube cube) {
      int layer = sectionY - minSectionY;
      if (layer < 0 || layer >= Sbc.MAX_LAYERS) {
        throw new IllegalArgumentException(
            "section "
                + sectionY
                + " is outside layers 0.."
                + (Sbc.MAX_LAYERS - 1)
                + " from minSectionY "
                + minSectionY);
      }
      cubes[layer] = Objects.requireNonNull(cube, "cube");
      sectionMask |= 1L << layer;
      return this;
    }

    /**
     * Returns the index this column's palette holds for {@code state}, adding it if new.
     *
     * <p>For a copier that builds {@link Cube}s itself rather than handing over string arrays.
     *
     * @param state the block state string
     * @return the palette index
     */
    public int paletteIndex(String state) {
      return palette.add(state);
    }

    /**
     * Builds the column.
     *
     * @return the column
     */
    public ChunkColumn build() {
      return new ChunkColumn(
          chunkX, chunkZ, minSectionY, sectionMask, palette.build(), cubes.clone());
    }
  }
}
