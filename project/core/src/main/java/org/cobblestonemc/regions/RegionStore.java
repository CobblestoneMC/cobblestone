/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.cobblestonemc.FutureOr;
import org.jetbrains.annotations.Nullable;

/**
 * Every built {@link RegionSection} of one domain, shared by every search over it.
 *
 * <p><b>Built once, kept in memory.</b> Building a section is most of what a search over new
 * terrain spends, and a section only needs building again when a block it was read from changes, so
 * a section built once serves every later search, by every agent, until then.
 *
 * <p><b>Two kinds of section in memory.</b> <em>Pinned</em> sections are kept whatever the budget,
 * until their last pin is released: a navigating player's surroundings, so the short searches that
 * re-plan around them never build. The rest are kept up to a byte budget, least recently used first
 * out. The two are held apart, so evicting never has to look past a pinned section.
 *
 * <p><b>Changes invalidate.</b> {@link #invalidate} drops a section, and the next search to need it
 * builds it again. A section being built when its terrain changes is handed to the search that
 * asked for it — it is at most a moment stale — but not kept. Only seams cross sections, and seams
 * are built per search, so a change never reaches a neighbour.
 *
 * <p>Thread-safe. Searches on several workers, block-change listeners on the server thread and
 * builds all use it at once; its maps are guarded by the store's own lock, and nothing slow —
 * building — happens while holding it.
 *
 * @param <C> the platform's terrain
 * @param <K> the terrain's classes
 */
public final class RegionStore<C, K extends Enum<K> & RegionKind> {

  private final RegionSpec<C, K> spec;
  private final long memoryBudget;
  private final Executor builds;
  private final ThreadLocal<RegionSectionBuilder<C, K>> builders;

  // --- guarded by this ---

  /** Sections not pinned, least recently used first. */
  private final LinkedHashMap<Long, RegionSection> memory = new LinkedHashMap<>(256, 0.75f, true);

  private long memoryBytes;

  /** Pinned sections that have been built. */
  private final Map<Long, RegionSection> pinned = new HashMap<>();

  private long pinnedBytes;

  /** How many pins hold each section, built or not. */
  private final Map<Long, Integer> pinCounts = new HashMap<>();

  /** Each owner's pinned sections. */
  private final Map<Object, Set<Long>> pins = new HashMap<>();

  private final Map<Long, CompletableFuture<RegionSection>> loading = new HashMap<>();
  private final Set<Long> changedWhileLoading = new HashSet<>();

  private long memoryHits;
  private long built;
  private long buildNanos;
  private long evictions;

  /**
   * Creates a store.
   *
   * @param spec how the domain's terrain is cut into regions
   * @param memoryBudget how many bytes of sections not pinned to keep in memory
   * @param builds where a section is built when its terrain arrives later rather than at once
   */
  public RegionStore(RegionSpec<C, K> spec, long memoryBudget, Executor builds) {
    this.spec = spec;
    this.memoryBudget = memoryBudget;
    this.builds = builds;
    this.builders = ThreadLocal.withInitial(() -> new RegionSectionBuilder<>(spec));
  }

  /**
   * Creates a store that keeps everything, on the caller's thread: one search's worth, as for a
   * benchmark or a test.
   *
   * @param spec how the domain's terrain is cut into regions
   * @param <C> the platform's terrain
   * @param <K> the terrain's classes
   * @return the store
   */
  public static <C, K extends Enum<K> & RegionKind> RegionStore<C, K> inMemory(
      RegionSpec<C, K> spec) {
    return new RegionStore<>(spec, Long.MAX_VALUE, Runnable::run);
  }

  /**
   * Returns how this store's sections are built.
   *
   * @return the spec
   */
  public RegionSpec<C, K> spec() {
    return spec;
  }

  /**
   * Returns a section, from memory or by building it from terrain {@code source} supplies.
   *
   * <p>Immediate when the section is in memory, when its terrain answered at once, or when it is
   * wholly outside the domain, which is never read. Otherwise a future that completes once the
   * section is built; a search should keep what it was handed rather than ask again, since a tight
   * budget may already have evicted it.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @param source where to read the terrain if the section has to be built
   * @return the section
   */
  public FutureOr<RegionSection> section(
      int sectionX, int sectionY, int sectionZ, TerrainSource<C> source) {
    if (spec.outside(sectionX, sectionY, sectionZ)) {
      return FutureOr.of(RegionSection.empty(spec, sectionX, sectionY, sectionZ));
    }
    long key = RegionSection.key(sectionX, sectionY, sectionZ);
    CompletableFuture<RegionSection> promise;
    synchronized (this) {
      RegionSection known = pinned.get(key);
      if (known == null) {
        known = memory.get(key);
      }
      if (known != null) {
        memoryHits++;
        return FutureOr.of(known);
      }
      CompletableFuture<RegionSection> inFlight = loading.get(key);
      if (inFlight != null) {
        return FutureOr.from(inFlight);
      }
      // Registered before any work starts: terrain that answers at once completes the promise
      // inside this call, and its completion has to find the entry to clear.
      promise = new CompletableFuture<>();
      loading.put(key, promise);
    }
    build(sectionX, sectionY, sectionZ, key, source, promise);
    return FutureOr.from(promise);
  }

  private void build(
      int sx,
      int sy,
      int sz,
      long key,
      TerrainSource<C> source,
      CompletableFuture<RegionSection> to) {
    FutureOr<C> terrain;
    try {
      terrain = source.section(sx, sy, sz);
    } catch (RuntimeException failed) {
      fail(key, to, failed);
      return;
    }
    if (terrain.isImmediate()) {
      try {
        settle(key, built(sx, sy, sz, terrain.value()), terrain.value(), to);
      } catch (RuntimeException failed) {
        // Failed, not thrown: left in flight, the section would park every later search on it.
        fail(key, to, failed);
      }
      return;
    }
    // Built off the thread the terrain arrived on, which on a live server may be the main thread.
    terrain
        .future()
        .whenCompleteAsync(
            (arrived, failure) -> {
              try {
                C readable = failure == null ? arrived : null;
                settle(key, built(sx, sy, sz, readable), readable, to);
              } catch (RuntimeException failed) {
                fail(key, to, failed);
              }
            },
            builds);
  }

  private RegionSection built(int sx, int sy, int sz, @Nullable C terrain) {
    long started = System.nanoTime();
    RegionSection section = builders.get().build(sx, sy, sz, terrain);
    long took = System.nanoTime() - started;
    synchronized (this) {
      built++;
      buildNanos += took;
    }
    return section;
  }

  /**
   * Keeps a section that has been built, unless its terrain changed meanwhile, and hands it over.
   *
   * <p>A section whose terrain could not be read is handed over but not kept: the terrain may be
   * generated, or a load policy relax, and a remembered empty section would wall it off for every
   * later search.
   */
  private void settle(
      long key, RegionSection section, @Nullable C terrain, CompletableFuture<RegionSection> to) {
    synchronized (this) {
      loading.remove(key);
      boolean changed = changedWhileLoading.remove(key);
      if (!changed && terrain != null) {
        remember(key, section);
      }
    }
    to.complete(section);
  }

  private void fail(long key, CompletableFuture<RegionSection> to, Throwable failure) {
    synchronized (this) {
      loading.remove(key);
      changedWhileLoading.remove(key);
    }
    to.completeExceptionally(failure);
  }

  /**
   * Pins the sections within {@code radius} of a section for an owner, releasing whatever the owner
   * had pinned before.
   *
   * <p>A pinned section is kept in memory whatever the budget, once built; pinning does not build
   * it. A section pinned by several owners stays until the last releases it.
   *
   * @param owner whoever the pin is for, such as a navigating player's id
   * @param sectionX the centre section X
   * @param sectionY the centre section Y
   * @param sectionZ the centre section Z
   * @param radius how many sections out from the centre, along every axis
   */
  public synchronized void pin(Object owner, int sectionX, int sectionY, int sectionZ, int radius) {
    Set<Long> wanted = new HashSet<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dy = -radius; dy <= radius; dy++) {
        for (int dz = -radius; dz <= radius; dz++) {
          wanted.add(RegionSection.key(sectionX + dx, sectionY + dy, sectionZ + dz));
        }
      }
    }
    Set<Long> before = pins.put(owner, wanted);
    for (long key : wanted) {
      if (before == null || !before.contains(key)) {
        hold(key);
      }
    }
    if (before != null) {
      for (long key : before) {
        if (!wanted.contains(key)) {
          letGo(key);
        }
      }
    }
    trim(Long.MIN_VALUE);
  }

  /**
   * Releases everything an owner has pinned. Its sections are then kept only as the budget allows.
   *
   * @param owner whoever the pin was for
   */
  public synchronized void release(Object owner) {
    Set<Long> held = pins.remove(owner);
    if (held == null) {
      return;
    }
    for (long key : held) {
      letGo(key);
    }
    trim(Long.MIN_VALUE);
  }

  private void hold(long key) {
    if (pinCounts.merge(key, 1, Integer::sum) == 1) {
      RegionSection section = memory.remove(key);
      if (section != null) {
        memoryBytes -= section.footprintBytes();
        pinned.put(key, section);
        pinnedBytes += section.footprintBytes();
      }
    }
  }

  private void letGo(long key) {
    if (pinCounts.merge(key, -1, Integer::sum) == 0) {
      pinCounts.remove(key);
      RegionSection section = pinned.remove(key);
      if (section != null) {
        pinnedBytes -= section.footprintBytes();
        // Most recently used: the owner was just near it.
        memory.put(key, section);
        memoryBytes += section.footprintBytes();
      }
    }
  }

  /**
   * Forgets a section because a block it was read from changed: one in it, or, since a class may
   * read the cells over and under it, one just above or below it.
   *
   * <p>Cheap, and safe to call on the server thread for every block event.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   */
  public synchronized void invalidate(int sectionX, int sectionY, int sectionZ) {
    long key = RegionSection.key(sectionX, sectionY, sectionZ);
    RegionSection dropped = memory.remove(key);
    if (dropped != null) {
      memoryBytes -= dropped.footprintBytes();
    }
    dropped = pinned.remove(key);
    if (dropped != null) {
      pinnedBytes -= dropped.footprintBytes();
    }
    if (loading.containsKey(key)) {
      changedWhileLoading.add(key);
    }
  }

  /**
   * Returns whether a section is in memory, pinned or not.
   *
   * @param sectionX the section X
   * @param sectionY the section Y
   * @param sectionZ the section Z
   * @return {@code true} if it is
   */
  public synchronized boolean inMemory(int sectionX, int sectionY, int sectionZ) {
    long key = RegionSection.key(sectionX, sectionY, sectionZ);
    return memory.containsKey(key) || pinned.containsKey(key);
  }

  /**
   * Returns what the store has done so far.
   *
   * @return the counters
   */
  public synchronized Stats stats() {
    return new Stats(
        memory.size(),
        memoryBytes,
        pinned.size(),
        pinnedBytes,
        memoryHits,
        built,
        buildNanos,
        evictions);
  }

  /**
   * What a store has done.
   *
   * @param unpinnedSections sections held in memory now that are not pinned, within the budget
   * @param unpinnedBytes their approximate footprint
   * @param pinnedSections pinned sections held in memory now, beyond the budget
   * @param pinnedBytes their approximate footprint
   * @param memoryHits sections answered from memory
   * @param builds sections built from terrain
   * @param buildNanos time spent building them
   * @param evictions sections dropped from memory for the budget
   */
  public record Stats(
      int unpinnedSections,
      long unpinnedBytes,
      int pinnedSections,
      long pinnedBytes,
      long memoryHits,
      long builds,
      long buildNanos,
      long evictions) {}

  /** Puts a section in memory as most recently used, evicting the least recent past the budget. */
  private void remember(long key, RegionSection section) {
    if (pinCounts.containsKey(key)) {
      RegionSection previous = pinned.put(key, section);
      if (previous != null) {
        pinnedBytes -= previous.footprintBytes();
      }
      pinnedBytes += section.footprintBytes();
      return;
    }
    RegionSection previous = memory.put(key, section);
    if (previous != null) {
      memoryBytes -= previous.footprintBytes();
    }
    memoryBytes += section.footprintBytes();
    trim(key);
  }

  /** Evicts the least recently used sections past the budget, but never {@code keep}. */
  private void trim(long keep) {
    Iterator<Map.Entry<Long, RegionSection>> eldest = memory.entrySet().iterator();
    while (memoryBytes > memoryBudget && eldest.hasNext()) {
      Map.Entry<Long, RegionSection> entry = eldest.next();
      if (entry.getKey() == keep) {
        continue; // never the one just asked for
      }
      memoryBytes -= entry.getValue().footprintBytes();
      eldest.remove();
      evictions++;
    }
  }
}
