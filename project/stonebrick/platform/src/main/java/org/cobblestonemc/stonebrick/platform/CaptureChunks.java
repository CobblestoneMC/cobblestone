/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.stonebrick.format.CaptureDirectory;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;

/**
 * Reads a capture's chunks directly, outside a search.
 *
 * <p>For tooling that wants the terrain rather than a solve over it — profiling it, measuring it,
 * drawing it. Going through {@link StonebrickPlatformApi} would drag in a scheduler, a chunk
 * provider and a simulated disk for no reason, and would charge IO against a run that is not one.
 */
public final class CaptureChunks {

  private CaptureChunks() {}

  /**
   * Returns the chunk coordinates a capture holds for a world.
   *
   * @param capture the capture
   * @param worldKey the world key
   * @return a list of {@code [chunkX, chunkZ]}
   * @throws IOException if the capture cannot be listed
   */
  public static List<int[]> columns(Capture capture, String worldKey) throws IOException {
    List<int[]> coordinates = new ArrayList<>();
    for (var file : CaptureDirectory.at(capture.root()).listColumns(worldKey)) {
      coordinates.add(CaptureDirectory.parseColumnName(file.getFileName().toString()));
    }
    return coordinates;
  }

  /**
   * Wraps a decoded column as a readable chunk.
   *
   * <p>Reads outside the capture answer as unknown and are counted on the capture's own log, the
   * same as they would be during a search — a tool that quietly read air past the edge would draw
   * or measure a world that is not there.
   *
   * @param capture the capture
   * @param column the decoded column
   * @param worldKey the world key
   * @return the chunk
   * @throws CaptureFormatException if the trait table does not describe the column's blocks
   */
  public static MinecraftChunk chunk(Capture capture, ChunkColumn column, String worldKey)
      throws CaptureFormatException {
    return StonebrickChunk.of(column, capture.traits(), worldKey, capture.missing());
  }
}
