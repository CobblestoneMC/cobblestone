/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.stonebrick.bench;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.ChunkFetch;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.lod.CoarseSearch;
import org.cobblestonemc.minecraft.lod.SectionProfile;
import org.cobblestonemc.minecraft.lod.SectionProfiler;
import org.cobblestonemc.stonebrick.platform.StonebrickPlatformApi;
import org.jetbrains.annotations.Nullable;

/**
 * Profiles a capture's sections on demand, remembering what it has done.
 *
 * <p>Stands in for the pyramid that is not built yet: every profile is computed the first time it
 * is asked for and kept for the rest of the run. That is the warm-cache case the design expects to
 * be normal, reached by paying for it once rather than by loading it from disk.
 *
 * <p><b>Reads go through the simulated disk</b>, the same one the search's own chunk fetches are
 * charged against. Under a {@code zero} profile that is still effectively free and every answer is
 * immediate; under {@code spinning} or {@code nvme} a column that is not resident comes back
 * pending, the coarse search stops, and the fine search parks. That is the only thing in the bench
 * that exercises the park path a live server will be in constantly, and it is what puts the coarse
 * layer's chunk reads on the same clock as everything else.
 */
public final class CaptureProfiles implements CoarseSearch.SectionProfiles {

  private final StonebrickPlatformApi platform;
  private final String world;
  private final SectionProfiler profiler = new SectionProfiler();
  private final Map<Long, SectionProfile> sections = new HashMap<>();
  private final Map<Long, MinecraftChunk> chunks = new HashMap<>();

  /**
   * Columns being read right now, so 26 neighbours asking for one column queue one read.
   *
   * <p>Dropped as soon as the read lands, at which point {@link #chunks} answers immediately -- the
   * progress guarantee {@link CoarseSearch.SectionProfiles} requires.
   */
  private final Map<Long, CompletableFuture<MinecraftChunk>> inFlight = new HashMap<>();

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
   * @param platform the platform whose disk the reads are charged against
   * @param world the world key
   */
  public CaptureProfiles(StonebrickPlatformApi platform, String world) {
    this.platform = platform;
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

  /**
   * Returns how many chunk columns the profiler read.
   *
   * <p>The number that decides whether this layer is affordable on a live server. Here the capture
   * is already in memory, so these reads cost nothing and are deliberately kept out of the IO model
   * — but in production every one of them is a chunk fetch, paid before the fine search takes its
   * first step.
   *
   * @return the chunk columns read
   */
  public int chunkColumns() {
    return chunks.size();
  }

  @Override
  // Always immediate: the capture is held in memory, so there is nothing to wait for. The
  // search's park path is exercised instead by the simulated-IO source.
  public FutureOr<SectionProfile> at(int sectionX, int sectionY, int sectionZ) {
    long key = CoarseSearch.key(sectionX, sectionY, sectionZ);
    if (sections.containsKey(key)) {
      return FutureOr.of(sections.get(key));
    }
    long column = ((long) sectionX << 32) | (sectionZ & 0xFFFF_FFFFL);
    if (chunks.containsKey(column)) {
      return FutureOr.of(profiled(key, chunks.get(column), sectionY));
    }
    // from(), not ofFuture(): under a zero-IO profile the read completes on the spot, and wrapping
    // an already-finished future as Pending would park the search for a chunk it already has.
    return FutureOr.<SectionProfile>from(
        fetch(column, sectionX, sectionZ).thenApply(chunk -> profiled(key, chunk, sectionY)));
  }

  /** Profiles a resident column's section and remembers the answer. */
  private @Nullable SectionProfile profiled(
      long key, @Nullable MinecraftChunk chunk, int sectionY) {
    SectionProfile profile = chunk == null ? null : profiler.profile(chunk, sectionY);
    sections.put(key, profile);
    return profile;
  }

  /** Starts a read for a column, or joins the one already going. */
  private CompletableFuture<MinecraftChunk> fetch(long column, int chunkX, int chunkZ) {
    CompletableFuture<MinecraftChunk> existing = inFlight.get(column);
    if (existing != null) {
      return existing;
    }
    CompletableFuture<MinecraftChunk> reading =
        platform
            .fetchForProfile(world, chunkX, chunkZ, beyond)
            .thenApply(
                fetch -> fetch instanceof ChunkFetch.Success success ? success.chunk() : null)
            .whenComplete(
                (chunk, throwable) -> {
                  chunks.put(column, chunk);
                  inFlight.remove(column);
                });
    inFlight.put(column, reading);
    return reading;
  }
}
