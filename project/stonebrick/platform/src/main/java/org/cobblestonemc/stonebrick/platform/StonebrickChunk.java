/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import org.cobblestonemc.minecraft.MinecraftBlock;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.UnknownBlock;
import org.cobblestonemc.stonebrick.format.BlockTraits;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.format.TraitTable;

/**
 * A {@link MinecraftChunk} over one captured column.
 *
 * <p>Traits are resolved to blocks once, when the column is decoded, so that {@link #block} costs
 * two array indexes rather than a palette lookup, a hash of a block state string, and a string
 * comparison. A solve reads tens of millions of blocks; this is the path that decides whether the
 * benchmark is measuring the search or measuring the harness.
 */
final class StonebrickChunk implements MinecraftChunk {

  private final ChunkColumn column;
  private final StonebrickBlock[] blocks;
  private final String worldKey;
  private final MissingCaptureLog missing;

  private StonebrickChunk(
      ChunkColumn column, StonebrickBlock[] blocks, String worldKey, MissingCaptureLog missing) {
    this.column = column;
    this.blocks = blocks;
    this.worldKey = worldKey;
    this.missing = missing;
  }

  /**
   * Decodes a column into a readable chunk, resolving its whole palette up front.
   *
   * @param column the captured column
   * @param traits the capture's trait table
   * @param worldKey the world key, for reporting missing reads
   * @param missing the log to record reads outside the capture
   * @return the chunk
   * @throws org.cobblestonemc.stonebrick.format.CaptureFormatException if the trait table does not
   *     describe some block state the column contains
   */
  static StonebrickChunk of(
      ChunkColumn column, TraitTable traits, String worldKey, MissingCaptureLog missing)
      throws org.cobblestonemc.stonebrick.format.CaptureFormatException {
    BlockTraits[] resolved = traits.resolve(column.palette());
    StonebrickBlock[] blocks = new StonebrickBlock[resolved.length];
    for (int i = 0; i < resolved.length; i++) {
      blocks[i] = new StonebrickBlock(resolved[i]);
    }
    return new StonebrickChunk(column, blocks, worldKey, missing);
  }

  @Override
  public MinecraftBlock block(int localX, int y, int localZ) {
    int index = column.paletteIndexOrAbsent(localX, y, localZ);
    if (index == ChunkColumn.ABSENT) {
      // The column exists but this cube was outside the captured vertical band. Reading as unknown
      // makes it behave exactly like a wall, which is why the read has to be recorded: otherwise a
      // capture that is one cube too shallow yields a plausible number and no way to notice.
      missing.missingCube(worldKey, column.chunkX(), column.chunkZ(), y);
      return UnknownBlock.INSTANCE;
    }
    return blocks[index];
  }
}
