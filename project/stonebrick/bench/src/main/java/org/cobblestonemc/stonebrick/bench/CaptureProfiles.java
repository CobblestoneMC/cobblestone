/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.util.HashMap;
import java.util.Map;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.lod.CoarseSearch;
import org.cobblestonemc.minecraft.lod.SectionProfile;
import org.cobblestonemc.minecraft.lod.SectionProfiler;
import org.cobblestonemc.stonebrick.format.CaptureFormatException;
import org.cobblestonemc.stonebrick.format.ChunkColumn;
import org.cobblestonemc.stonebrick.platform.Capture;
import org.cobblestonemc.stonebrick.platform.CaptureChunks;

/**
 * Profiles a capture's sections on demand, remembering what it has done.
 *
 * <p>Stands in for the pyramid that is not built yet: every profile is computed the first time it
 * is asked for and kept for the rest of the run. That is the warm-cache case the design expects to
 * be normal, reached by paying for it once rather than by loading it from disk.
 *
 * <p>Answers synchronously, which a live server could not — see {@link
 * org.cobblestonemc.minecraft.lod.CoarseHeuristic}. It is possible here only because the capture is
 * already in memory.
 */
public final class CaptureProfiles implements CoarseSearch.SectionProfiles {

  private final Capture capture;
  private final String world;
  private final SectionProfiler profiler = new SectionProfiler();
  private final Map<Long, SectionProfile> sections = new HashMap<>();
  private final Map<Long, MinecraftChunk> chunks = new HashMap<>();

  /**
   * Reads past the capture's edge, kept apart from the capture's own log.
   *
   * <p>The coarse search spreads outward from the destination and is expected to ask about terrain
   * the fine search will never touch. That is the edge of what is known, not a degenerate run.
   */
  private final org.cobblestonemc.stonebrick.platform.MissingCaptureLog beyond =
      new org.cobblestonemc.stonebrick.platform.MissingCaptureLog();

  /**
   * Creates a profile source over one world of a capture.
   *
   * @param capture the capture
   * @param world the world key
   */
  public CaptureProfiles(Capture capture, String world) {
    this.capture = capture;
    this.world = world;
  }

  /**
   * Returns how far past the capture the coarse search reached, as a chunk count.
   *
   * @return the number of chunks profiled outside the capture
   */
  public int beyondCapture() {
    return beyond.chunks().size();
  }

  /**
   * Returns how many sections have been profiled.
   *
   * @return the profiled count
   */
  public int profiled() {
    return sections.size();
  }

  @Override
  public SectionProfile at(int sectionX, int sectionY, int sectionZ) {
    long key = CoarseSearch.key(sectionX, sectionY, sectionZ);
    if (sections.containsKey(key)) {
      return sections.get(key);
    }
    MinecraftChunk chunk = chunkAt(sectionX, sectionZ);
    SectionProfile profile = chunk == null ? null : profiler.profile(chunk, sectionY);
    sections.put(key, profile);
    return profile;
  }

  private MinecraftChunk chunkAt(int chunkX, int chunkZ) {
    long key = ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    if (chunks.containsKey(key)) {
      return chunks.get(key);
    }
    MinecraftChunk chunk = null;
    try {
      ChunkColumn column = capture.column(world, chunkX, chunkZ);
      if (column != null) {
        chunk = CaptureChunks.chunk(capture, column, world, beyond);
      }
    } catch (CaptureFormatException e) {
      chunk = null;
    }
    chunks.put(key, chunk);
    return chunk;
  }
}
