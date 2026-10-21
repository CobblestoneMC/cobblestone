/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.regions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
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
 * <p><b>Three places a column can come from,</b> tried in order: memory, disk, and building it from
 * the terrain. Building is the expensive one — it is most of what a search over new terrain spends
 * — and a column only needs building again when a block in it changes, so a column built once
 * serves every later search, by every agent, until then; and through {@link RegionFiles}, across
 * restarts. A column built but not yet written is held until the next {@link #flush}, whatever the
 * budget, so evicting it never loses it.
 *
 * <p><b>Two kinds of column in memory.</b> <em>Pinned</em> columns are kept whatever the budget,
 * until their last pin is released: a navigating player's surroundings, so the short searches that
 * re-plan around them never build. The rest are kept up to a byte budget, least recently used first
 * out. The two are held apart, so evicting never has to look past a pinned column.
 *
 * <p><b>Changes invalidate.</b> {@link #invalidate} drops a column from memory, and from disk at
 * the next flush, and the next search to need it builds it again. A column being built when its
 * terrain changes is handed to the search that asked for it — it is at most a moment stale — but
 * not kept. Only seams cross columns, and seams are built per search, so a change never reaches a
 * neighbour.
 *
 * <p>Thread-safe. Searches on several workers, block-change listeners on the server thread and
 * builds all use it at once; its maps are guarded by the store's own lock, and nothing slow —
 * building, reading, writing — happens while holding it.
 *
 * @param <C> the platform's column of terrain
 */
public final class RegionStore<C> {

  private final RegionSpec<C> spec;
  private final long memoryBudget;
  private final @Nullable RegionFiles files;
  private final Executor disk;
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

  /** Built since the last flush, and not yet on disk. Kept whatever the budget says. */
  private final Map<Long, RegionColumn> unwritten = new HashMap<>();

  /** Invalidated since the last flush: to come off disk. */
  private final Set<Long> removed = new HashSet<>();

  private final Map<Long, CompletableFuture<RegionColumn>> loading = new HashMap<>();
  private final Set<Long> changedWhileLoading = new HashSet<>();

  private long memoryHits;
  private long diskLoads;
  private long diskNanos;
  private long built;
  private long buildNanos;
  private long evictions;

  /**
   * Creates a store.
   *
   * @param spec how the world's terrain is cut into regions
   * @param memoryBudget how many bytes of columns not pinned to keep in memory
   * @param directory where to keep columns on disk, or {@code null} to keep them only in memory
   * @param disk where disk reads and writes run; one thread, so they never race
   * @param builds where a column is built when its terrain arrives later rather than at once
   */
  public RegionStore(
      RegionSpec<C> spec,
      long memoryBudget,
      @Nullable Path directory,
      Executor disk,
      Executor builds) {
    this.spec = spec;
    this.memoryBudget = memoryBudget;
    this.files = directory == null ? null : new RegionFiles(directory, spec);
    this.disk = disk;
    this.builds = builds;
    this.builders = ThreadLocal.withInitial(() -> new RegionColumnBuilder<>(spec));
  }

  /**
   * Creates a store that keeps everything in memory, on the caller's thread: one search's worth, as
   * for a benchmark or a test.
   *
   * @param spec how the world's terrain is cut into regions
   * @param <C> the platform's column of terrain
   * @return the store
   */
  public static <C> RegionStore<C> inMemory(RegionSpec<C> spec) {
    return new RegionStore<>(spec, Long.MAX_VALUE, null, Runnable::run, Runnable::run);
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
   * Returns a column, from memory, from disk, or by building it from terrain {@code source}
   * supplies.
   *
   * <p>Immediate when the column is in memory, or when every step on the way to it — the disk read,
   * the terrain — answered at once. Otherwise a future that completes once the column is ready; a
   * search should keep what it was handed rather than ask again, since a tight budget may already
   * have evicted it.
   *
   * @param columnX the column X
   * @param columnZ the column Z
   * @param source where to read the terrain if the column has to be built
   * @return the column
   */
  public FutureOr<RegionColumn> column(int columnX, int columnZ, TerrainSource<C> source) {
    long key = key(columnX, columnZ);
    CompletableFuture<RegionColumn> promise;
    synchronized (this) {
      RegionColumn known = pinned.get(key);
      if (known == null) {
        known = memory.get(key);
      }
      if (known == null) {
        known = unwritten.get(key);
        if (known != null) {
          remember(key, known);
        }
      }
      if (known != null) {
        memoryHits++;
        return FutureOr.of(known);
      }
      CompletableFuture<RegionColumn> inFlight = loading.get(key);
      if (inFlight != null) {
        return FutureOr.from(inFlight);
      }
      // Registered before any work starts: a disk read or terrain that answers at once completes
      // the promise inside this call, and its completion has to find the entry to clear.
      promise = new CompletableFuture<>();
      loading.put(key, promise);
    }
    load(columnX, columnZ, source, promise);
    return FutureOr.from(promise);
  }

  private void load(
      int columnX, int columnZ, TerrainSource<C> source, CompletableFuture<RegionColumn> to) {
    boolean stale;
    synchronized (this) {
      // Invalidated and not yet removed from disk: what is there is the terrain before the change.
      stale = removed.contains(key(columnX, columnZ));
    }
    if (files == null || stale) {
      build(columnX, columnZ, source, to);
      return;
    }
    disk.execute(
        () -> {
          RegionColumn stored;
          long started = System.nanoTime();
          try {
            stored = files.read(columnX, columnZ);
          } catch (IOException | RuntimeException unreadable) {
            // A file that cannot be read is a column that has to be built again; the next write
            // of that column replaces it.
            stored = null;
          }
          long took = System.nanoTime() - started;
          synchronized (this) {
            diskNanos += took;
            if (stored != null) {
              diskLoads++;
            }
          }
          if (stored != null) {
            settle(columnX, columnZ, stored, true, false, to);
          } else {
            // Built on the build pool: the disk thread is one thread, shared by every world's reads
            // and writes, and a build is far slower than a read.
            builds.execute(() -> build(columnX, columnZ, source, to));
          }
        });
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
      boolean readable = terrain.value() != null;
      settle(columnX, columnZ, built(columnX, columnZ, terrain.value()), readable, readable, to);
      return;
    }
    // Built off the thread the terrain arrived on, which on a live server may be the main thread.
    terrain
        .future()
        .whenCompleteAsync(
            (arrived, failure) -> {
              try {
                C readable = failure == null ? arrived : null;
                boolean real = readable != null;
                settle(columnX, columnZ, built(columnX, columnZ, readable), real, real, to);
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
   * Keeps a column that has arrived, unless its terrain changed meanwhile, and hands it over.
   *
   * <p>A column whose terrain could not be read is handed over but not kept: the terrain may be
   * generated, or a load policy relax, and a remembered empty column would wall it off for every
   * later search.
   *
   * @param keep whether it describes real terrain, built or read back
   * @param fresh whether it was built just now, and so belongs on disk
   */
  private void settle(
      int columnX,
      int columnZ,
      RegionColumn column,
      boolean keep,
      boolean fresh,
      CompletableFuture<RegionColumn> to) {
    long key = key(columnX, columnZ);
    synchronized (this) {
      loading.remove(key);
      boolean changed = changedWhileLoading.remove(key);
      if (!changed && keep) {
        remember(key, column);
        if (fresh && files != null) {
          unwritten.put(key, column);
          removed.remove(key);
        }
      }
    }
    to.complete(column);
  }

  private void fail(
      int columnX, int columnZ, CompletableFuture<RegionColumn> to, Throwable failure) {
    synchronized (this) {
      loading.remove(key(columnX, columnZ));
      changedWhileLoading.remove(key(columnX, columnZ));
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
        wanted.add(key(columnX + dx, columnZ + dz));
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
    long key = key(columnX, columnZ);
    RegionColumn dropped = memory.remove(key);
    if (dropped != null) {
      memoryBytes -= dropped.footprintBytes();
    }
    dropped = pinned.remove(key);
    if (dropped != null) {
      pinnedBytes -= dropped.footprintBytes();
    }
    unwritten.remove(key);
    if (loading.containsKey(key)) {
      changedWhileLoading.add(key);
    }
    if (files != null) {
      removed.add(key);
    }
  }

  /**
   * Returns whether a column is in memory, pinned or not. Says nothing about disk.
   *
   * @param columnX the column X
   * @param columnZ the column Z
   * @return {@code true} if it is
   */
  public synchronized boolean inMemory(int columnX, int columnZ) {
    long key = key(columnX, columnZ);
    return memory.containsKey(key) || pinned.containsKey(key);
  }

  /**
   * Writes every column built since the last flush, and removes every invalidated one, on the disk
   * thread.
   *
   * <p>A write that fails puts its columns back, so the next flush tries again.
   *
   * @return a future completing when the write is done
   */
  public CompletableFuture<Void> flush() {
    if (files == null) {
      return CompletableFuture.completedFuture(null);
    }
    Map<Long, RegionColumn> writing;
    Set<Long> removing;
    synchronized (this) {
      if (unwritten.isEmpty() && removed.isEmpty()) {
        return CompletableFuture.completedFuture(null);
      }
      writing = new HashMap<>(unwritten);
      removing = new HashSet<>(removed);
      unwritten.clear();
      removed.clear();
    }
    return CompletableFuture.runAsync(
        () -> {
          try {
            files.write(writing.values(), removing);
          } catch (IOException failed) {
            synchronized (this) {
              // Not a column invalidated while the write ran: putting it back would serve, and
              // then write, the terrain before the change.
              writing.forEach(
                  (key, column) -> {
                    if (!removed.contains(key)) {
                      unwritten.putIfAbsent(key, column);
                    }
                  });
              removed.addAll(removing);
            }
            throw new UncheckedIOException(failed);
          }
        },
        disk);
  }

  /**
   * Releases the files the store holds open, on the disk thread. Unwritten columns are not written:
   * {@link #flush} first.
   *
   * @return a future completing once they are closed
   */
  public CompletableFuture<Void> close() {
    if (files == null) {
      return CompletableFuture.completedFuture(null);
    }
    return CompletableFuture.runAsync(
        () -> {
          try {
            files.close();
          } catch (IOException ignored) {
            // A handle that will not close is released when the process ends.
          }
        },
        disk);
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
        unwritten.size(),
        memoryHits,
        diskLoads,
        diskNanos,
        built,
        buildNanos,
        evictions,
        files == null ? 0 : files.bytesRead(),
        files == null ? 0 : files.bytesWritten());
  }

  /**
   * What a store has done.
   *
   * @param columnsInMemory columns held in memory now, not pinned
   * @param bytesInMemory their approximate footprint
   * @param columnsPinned pinned columns held in memory now
   * @param bytesPinned their approximate footprint
   * @param columnsUnwritten columns built and not yet on disk
   * @param memoryHits columns answered from memory
   * @param diskLoads columns read back from disk
   * @param diskNanos time spent reading the disk, including reads that found nothing
   * @param builds columns built from terrain
   * @param buildNanos time spent building them
   * @param evictions columns dropped from memory for the budget
   * @param diskBytesRead record bytes read
   * @param diskBytesWritten bytes written
   */
  public record Stats(
      int columnsInMemory,
      long bytesInMemory,
      int columnsPinned,
      long bytesPinned,
      int columnsUnwritten,
      long memoryHits,
      long diskLoads,
      long diskNanos,
      long builds,
      long buildNanos,
      long evictions,
      long diskBytesRead,
      long diskBytesWritten) {}

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

  private static long key(int columnX, int columnZ) {
    return ((long) columnX << 32) | (columnZ & 0xFFFF_FFFFL);
  }
}
