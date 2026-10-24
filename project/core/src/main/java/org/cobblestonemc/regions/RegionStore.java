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
 * Every built {@link RegionColumn} of one world, shared by every search over it.
 *
 * <p><b>Built once, kept in memory.</b> Building a column is most of what a search over new terrain
 * spends, and a column only needs building again when a block in it changes, so a column built once
 * serves every later search, by every agent, until then.
 *
 * <p><b>Two kinds of column in memory.</b> <em>Pinned</em> columns are kept whatever the budget,
 * until their last pin is released: a navigating player's surroundings, so the short searches that
 * re-plan around them never build. The rest are kept up to a byte budget, least recently used first
 * out. The two are held apart, so evicting never has to look past a pinned column.
 *
 * <p><b>Changes invalidate.</b> {@link #invalidate} drops a column, and the next search to need it
 * builds it again. A column being built when its terrain changes is handed to the search that asked
 * for it — it is at most a moment stale — but not kept. Only seams cross columns, and seams are
 * built per search, so a change never reaches a neighbour.
 *
 * <p>Thread-safe. Searches on several workers, block-change listeners on the server thread and
 * builds all use it at once; its maps are guarded by the store's own lock, and nothing slow —
 * building — happens while holding it.
 *
 * @param <C> the platform's column of terrain
 */
public final class RegionStore<C> {

  private final RegionSpec<C> spec;
  private final long memoryBudget;
  private final Executor builds;
  private final ThreadLocal<RegionColumnBuilder<C>> builders;

  // --- guarded by this ---

  /** Columns not pinned, least recently used first. */
  private final LinkedHashMap<Long, RegionColumn> memory = new LinkedHashMap<>(256, 0.75f, true);

  private long memoryBytes;

  /** Pinned columns that have been built. */
  private final Map<Long, RegionColumn> pinned = new HashMap<>();

  private long pinnedBytes;

  /** How many pins hold each column, built or not. */
  private final Map<Long, Integer> pinCounts = new HashMap<>();

  /** Each owner's pinned columns. */
  private final Map<Object, Set<Long>> pins = new HashMap<>();

  private final Map<Long, CompletableFuture<RegionColumn>> loading = new HashMap<>();
  private final Set<Long> changedWhileLoading = new HashSet<>();

  private long memoryHits;
  private long built;
  private long buildNanos;
  private long evictions;

  /**
   * Creates a store.
   *
   * @param spec how the world's terrain is cut into regions
   * @param memoryBudget how many bytes of columns not pinned to keep in memory
   * @param builds where a column is built when its terrain arrives later rather than at once
   */
  public RegionStore(RegionSpec<C> spec, long memoryBudget, Executor builds) {
    this.spec = spec;
    this.memoryBudget = memoryBudget;
    this.builds = builds;
    this.builders = ThreadLocal.withInitial(() -> new RegionColumnBuilder<>(spec));
  }

  /**
   * Creates a store that keeps everything, on the caller's thread: one search's worth, as for a
   * benchmark or a test.
   *
   * @param spec how the world's terrain is cut into regions
   * @param <C> the platform's column of terrain
   * @return the store
   */
  public static <C> RegionStore<C> inMemory(RegionSpec<C> spec) {
    return new RegionStore<>(spec, Long.MAX_VALUE, Runnable::run);
  }

  /**
   * Returns how this store's columns are built.
   *
   * @return the spec
   */
  public RegionSpec<C> spec() {
    return spec;
  }

  /**
   * Returns a column, from memory or by building it from terrain {@code source} supplies.
   *
   * <p>Immediate when the column is in memory, or when its terrain answered at once. Otherwise a
   * future that completes once the column is built; a search should keep what it was handed rather
   * than ask again, since a tight budget may already have evicted it.
   *
   * @param columnX the column X
   * @param columnZ the column Z
   * @param source where to read the terrain if the column has to be built
   * @return the column
   */
  public FutureOr<RegionColumn> column(int columnX, int columnZ, TerrainSource<C> source) {
    long key = RegionColumn.key(columnX, columnZ);
    CompletableFuture<RegionColumn> promise;
    synchronized (this) {
      RegionColumn known = pinned.get(key);
      if (known == null) {
        known = memory.get(key);
      }
      if (known != null) {
        memoryHits++;
        return FutureOr.of(known);
      }
      CompletableFuture<RegionColumn> inFlight = loading.get(key);
      if (inFlight != null) {
        return FutureOr.from(inFlight);
      }
      // Registered before any work starts: terrain that answers at once completes the promise
      // inside this call, and its completion has to find the entry to clear.
      promise = new CompletableFuture<>();
      loading.put(key, promise);
    }
    build(columnX, columnZ, source, promise);
    return FutureOr.from(promise);
  }

  private void build(
      int columnX, int columnZ, TerrainSource<C> source, CompletableFuture<RegionColumn> to) {
    FutureOr<C> terrain;
    try {
      terrain = source.column(columnX, columnZ);
    } catch (RuntimeException failed) {
      fail(columnX, columnZ, to, failed);
      return;
    }
    if (terrain.isImmediate()) {
      try {
        settle(columnX, columnZ, built(columnX, columnZ, terrain.value()), terrain.value(), to);
      } catch (RuntimeException failed) {
        // Failed, not thrown: left in flight, the column would park every later search on it.
        fail(columnX, columnZ, to, failed);
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
                settle(columnX, columnZ, built(columnX, columnZ, readable), readable, to);
              } catch (RuntimeException failed) {
                fail(columnX, columnZ, to, failed);
              }
            },
            builds);
  }

  private RegionColumn built(int columnX, int columnZ, @Nullable C terrain) {
    long started = System.nanoTime();
    RegionColumn column = builders.get().build(columnX, columnZ, terrain);
    long took = System.nanoTime() - started;
    synchronized (this) {
      built++;
      buildNanos += took;
    }
    return column;
  }

  /**
   * Keeps a column that has been built, unless its terrain changed meanwhile, and hands it over.
   *
   * <p>A column whose terrain could not be read is handed over but not kept: the terrain may be
   * generated, or a load policy relax, and a remembered empty column would wall it off for every
   * later search.
   */
  private void settle(
      int columnX,
      int columnZ,
      RegionColumn column,
      @Nullable C terrain,
      CompletableFuture<RegionColumn> to) {
    long key = RegionColumn.key(columnX, columnZ);
    synchronized (this) {
      loading.remove(key);
      boolean changed = changedWhileLoading.remove(key);
      if (!changed && terrain != null) {
        remember(key, column);
      }
    }
    to.complete(column);
  }

  private void fail(
      int columnX, int columnZ, CompletableFuture<RegionColumn> to, Throwable failure) {
    synchronized (this) {
      loading.remove(RegionColumn.key(columnX, columnZ));
      changedWhileLoading.remove(RegionColumn.key(columnX, columnZ));
    }
    to.completeExceptionally(failure);
  }

  /**
   * Pins the columns within {@code radius} of a column for an owner, releasing whatever the owner
   * had pinned before.
   *
   * <p>A pinned column is kept in memory whatever the budget, once built; pinning does not build
   * it. A column pinned by several owners stays until the last releases it.
   *
   * @param owner whoever the pin is for, such as a navigating player's id
   * @param columnX the centre column X
   * @param columnZ the centre column Z
   * @param radius how many columns out from the centre, along X and Z
   */
  public synchronized void pin(Object owner, int columnX, int columnZ, int radius) {
    Set<Long> wanted = new HashSet<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        wanted.add(RegionColumn.key(columnX + dx, columnZ + dz));
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
   * Releases everything an owner has pinned. Its columns are then kept only as the budget allows.
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
      RegionColumn column = memory.remove(key);
      if (column != null) {
        memoryBytes -= column.footprintBytes();
        pinned.put(key, column);
        pinnedBytes += column.footprintBytes();
      }
    }
  }

  private void letGo(long key) {
    if (pinCounts.merge(key, -1, Integer::sum) == 0) {
      pinCounts.remove(key);
      RegionColumn column = pinned.remove(key);
      if (column != null) {
        pinnedBytes -= column.footprintBytes();
        // Most recently used: the owner was just near it.
        memory.put(key, column);
        memoryBytes += column.footprintBytes();
      }
    }
  }

  /**
   * Forgets a column because a block in it changed.
   *
   * <p>Cheap, and safe to call on the server thread for every block event.
   *
   * @param columnX the column X
   * @param columnZ the column Z
   */
  public synchronized void invalidate(int columnX, int columnZ) {
    long key = RegionColumn.key(columnX, columnZ);
    RegionColumn dropped = memory.remove(key);
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
   * Returns whether a column is in memory, pinned or not.
   *
   * @param columnX the column X
   * @param columnZ the column Z
   * @return {@code true} if it is
   */
  public synchronized boolean inMemory(int columnX, int columnZ) {
    long key = RegionColumn.key(columnX, columnZ);
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
   * @param unpinnedColumns columns held in memory now that are not pinned, within the budget
   * @param unpinnedBytes their approximate footprint
   * @param pinnedColumns pinned columns held in memory now, beyond the budget
   * @param pinnedBytes their approximate footprint
   * @param memoryHits columns answered from memory
   * @param builds columns built from terrain
   * @param buildNanos time spent building them
   * @param evictions columns dropped from memory for the budget
   */
  public record Stats(
      int unpinnedColumns,
      long unpinnedBytes,
      int pinnedColumns,
      long pinnedBytes,
      long memoryHits,
      long builds,
      long buildNanos,
      long evictions) {}

  /** Puts a column in memory as most recently used, evicting the least recent past the budget. */
  private void remember(long key, RegionColumn column) {
    if (pinCounts.containsKey(key)) {
      RegionColumn previous = pinned.put(key, column);
      if (previous != null) {
        pinnedBytes -= previous.footprintBytes();
      }
      pinnedBytes += column.footprintBytes();
      return;
    }
    RegionColumn previous = memory.put(key, column);
    if (previous != null) {
      memoryBytes -= previous.footprintBytes();
    }
    memoryBytes += column.footprintBytes();
    trim(key);
  }

  /** Evicts the least recently used columns past the budget, but never {@code keep}. */
  private void trim(long keep) {
    Iterator<Map.Entry<Long, RegionColumn>> eldest = memory.entrySet().iterator();
    while (memoryBytes > memoryBudget && eldest.hasNext()) {
      Map.Entry<Long, RegionColumn> entry = eldest.next();
      if (entry.getKey() == keep) {
        continue; // never the one just asked for
      }
      memoryBytes -= entry.getValue().footprintBytes();
      eldest.remove();
      evictions++;
    }
  }
}
