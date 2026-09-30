/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.cobblestonemc.Cell;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.cobblestonemc.minecraft.MinecraftWorld;
import org.jetbrains.annotations.Nullable;

/**
 * Profiles a live world's sections, fetching the chunks it needs.
 *
 * <p>Written against {@link MinecraftWorld} rather than any one platform's classes, so Paper and
 * Sponge both get it without a copy, and so it can be pointed at a test world.
 *
 * <p><b>No pre-solve pass.</b> An earlier plan for this was to profile the whole corridor before
 * the fine search started, because {@code SolveHeuristic#estimate} could only answer synchronously
 * and a live server reaches chunks through a future. That is no longer necessary: the search parks
 * on an estimate that is not ready, so profiling happens on demand and only for the sections a
 * query actually reaches. It also removes the need to guess how far ahead to profile, which is the
 * part of the pre-solve design that had no good answer.
 *
 * <p>⚠️ <b>This is a per-solve cache, not the pyramid.</b> Every profile is computed fresh for each
 * navigate and thrown away after. Benchmarking says a long route touches thousands of chunk columns
 * and spends seconds of CPU on the flood fill, so a server running many searches wants the derived
 * data to outlive one solve. A {@link SectionProfile} describes terrain and nothing about the
 * agent, which is exactly what makes that sharing possible later.
 */
public final class WorldSectionProfiles implements CoarseSearch.SectionProfiles {

  private final MinecraftWorld world;
  private final Cell destination;
  private final SectionProfiler profiler = new SectionProfiler();

  /** Profiles by section key; a null value means "this section cannot be known". */
  private final Map<Long, FutureOr<SectionProfile>> sections = new ConcurrentHashMap<>();

  /**
   * Creates a profile source over a world.
   *
   * @param world the world to read
   * @param destination where the solve is heading, passed to the chunk provider as the hint it uses
   *     to decide how hard to try for a chunk
   */
  public WorldSectionProfiles(MinecraftWorld world, Cell destination) {
    this.world = world;
    this.destination = destination;
  }

  @Override
  public FutureOr<SectionProfile> at(int sectionX, int sectionY, int sectionZ) {
    long key = CoarseSearch.key(sectionX, sectionY, sectionZ);
    FutureOr<SectionProfile> known = sections.get(key);
    if (known != null) {
      // Remembered even while its fetch is in flight, so the 26 neighbours of an expansion that
      // all want the same column queue one read — and so a section asked about again after its
      // future completed answers immediately, which CoarseSearch.SectionProfiles requires.
      return known;
    }
    int floor = sectionY * CoarseSearch.SECTION;
    if (floor > world.maxY() || floor + CoarseSearch.SECTION - 1 < world.minY()) {
      // Outside the world's build range: not unknown terrain, just nowhere.
      FutureOr<SectionProfile> nowhere = FutureOr.of(null);
      sections.put(key, nowhere);
      return nowhere;
    }
    Cell anchor =
        new Cell(
            sectionX * CoarseSearch.SECTION,
            Math.max(world.minY(), Math.min(world.maxY(), floor)),
            sectionZ * CoarseSearch.SECTION);
    FutureOr<SectionProfile> profile =
        world.chunkAt(anchor, destination).map(chunk -> profiled(chunk, sectionY));
    // from(), so a chunk that was already resident does not park the search on a finished future.
    FutureOr<SectionProfile> settled =
        profile.isImmediate() ? profile : FutureOr.from(profile.future());
    sections.put(key, settled);
    return settled;
  }

  /**
   * Profiles one section of a column.
   *
   * <p>Synchronized because {@link SectionProfiler} reuses its scratch arrays between calls and a
   * chunk future completes on whatever thread the platform finished it on. Two profiles running at
   * once would interleave into each other's buffers and produce terrain that exists nowhere.
   */
  private synchronized @Nullable SectionProfile profiled(
      @Nullable MinecraftChunk chunk, int sectionY) {
    return chunk == null ? null : profiler.profile(chunk, sectionY);
  }

  /**
   * Returns how many sections this source has been asked about.
   *
   * @return the section count
   */
  public int profiled() {
    return sections.size();
  }
}
