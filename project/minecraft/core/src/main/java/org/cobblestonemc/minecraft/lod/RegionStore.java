/*
 * Cobblestone — a Minecraft navigation plugin.
 * Copyright (c) 2026 whimxiqal.
 *
 * Licensed under the MIT License. See the LICENSE file in the project root for full text.
 */

package org.cobblestonemc.minecraft.lod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.cobblestonemc.FutureOr;
import org.cobblestonemc.minecraft.MinecraftChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Every built {@link RegionColumn} of one world, shared by every search over it.
 *
 * <p><b>Three places a column can come from,</b> tried in order: memory, disk, and building it from
 * the chunk. Building is the expensive one — it is most of what a search over new terrain spends —
 * and a column only needs building again when a block in it changes, so a column built once serves
 * every later search, by every agent, until then; and through {@link RegionFiles}, across restarts.
 *
 * <p><b>Loaded chunks are always in memory; the rest is bounded; disk is the record.</b> A column
 * whose chunk the server has loaded ({@link #chunkLoaded}) is never evicted: it is where players
 * are, and so where searches start and guide paths run. Other columns are kept up to a byte budget,
 * least recently used first out. A column built but not yet written is held until the next {@link
 * #flush}, whatever the budget, so evicting it never loses it.
 *
 * <p><b>Changes invalidate.</b> {@link #invalidate} drops a column from memory and from disk at the
 * next flush, and the next search to need it builds it again. A column being built when its terrain
 * changes is handed to the search that asked for it — it is at most a moment stale — but not kept.
 * Only seams cross columns, and seams are built per search, so a change never reaches a neighbour.
 *
 * <p>Thread-safe. Searches on several workers, block-change listeners on the server thread and
 * background builds all use it at once; its maps are guarded by the store's own lock, and nothing
 * slow — building, reading, writing — happens while holding it.
 */
public final class RegionStore {

  private final int worldMinY;
  private final int worldMaxY;
  private final double detourRatio;
  private final long memoryBudget;
  private final @Nullable RegionFiles files;
  private final Executor disk;
  private final Executor builds;
  private final ThreadLocal<RegionColumnBuilder> builders;
  private final int columnMinY;
  private final int sections;

  // --- guarded by this ---
  private final LinkedHashMap<Long, RegionColumn> memory = new LinkedHashMap<>(256, 0.75f, true);
  private long memoryBytes;

  /** Built since the last flush, and not yet on disk. Kept whatever the budget says. */
  private final Map<Long, RegionColumn> unwritten = new HashMap<>();

  /** Invalidated since the last flush: to come off disk. */
  private final Set<Long> removed = new HashSet<>();

  /** Chunks the server has loaded, whose columns stay in memory. */
  private final Set<Long> loadedChunks = new HashSet<>();

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
   * @param worldMinY the world's lowest Y, inclusive
   * @param worldMaxY the world's highest Y, inclusive
   * @param detourRatio the fill's detour ratio; see {@link RegionGraph#DEFAULT_DETOUR_RATIO}
   * @param memoryBudget how many bytes of columns to keep in memory
   * @param files where columns are kept on disk, or {@code null} to keep them only in memory
   * @param disk where disk reads and writes run; one thread, so they never race
   * @param builds where a column is built when its chunk arrives later rather than at once
   */
  public RegionStore(
      int worldMinY,
      int worldMaxY,
      double detourRatio,
      long memoryBudget,
      @Nullable RegionFiles files,
      Executor disk,
      Executor builds) {
    this.worldMinY = worldMinY;
    this.worldMaxY = worldMaxY;
    this.detourRatio = detourRatio;
    this.memoryBudget = memoryBudget;
    this.files = files;
    this.disk = disk;
    this.builds = builds;
    this.builders =
        ThreadLocal.withInitial(() -> new RegionColumnBuilder(worldMinY, worldMaxY, detourRatio));
    this.columnMinY = RegionColumnBuilder.columnMinY(worldMinY);
    this.sections = RegionColumnBuilder.sections(worldMinY, worldMaxY);
  }

  /**
   * Creates a store that keeps everything in memory, on the caller's thread: one search's worth, as
   * for a benchmark or a test.
   *
   * @param worldMinY the world's lowest Y, inclusive
   * @param worldMaxY the world's highest Y, inclusive
   * @param detourRatio the fill's detour ratio
   * @return the store
   */
  public static RegionStore inMemory(int worldMinY, int worldMaxY, double detourRatio) {
    return new RegionStore(
        worldMinY, worldMaxY, detourRatio, Long.MAX_VALUE, null, Runnable::run, Runnable::run);
  }

  /**
   * Returns the lowest Y a column counts its cells from: the world's floor, rounded down to a
   * section.
   *
   * @return the column floor
   */
  public int columnMinY() {
    return columnMinY;
  }

  /**
   * Returns how many sections tall a column is.
   *
   * @return the section count
   */
  public int sections() {
    return sections;
  }

  /**
   * Returns the detour ratio this store's columns are filled with.
   *
   * @return the ratio
   */
  public double detourRatio() {
    return detourRatio;
  }

  /**
   * Returns a column, from memory, from disk, or by building it from a chunk {@code chunks}
   * supplies.
   *
   * <p>Immediate when the column is in memory, or when every step on the way to it — the disk read,
   * the chunk — answered at once. Otherwise a future that completes once the column is ready; it is
   * then in memory, though a search should keep what it was handed rather than ask again, since a
   * tight budget may already have evicted it.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param chunks where to read the chunk if the column has to be built
   * @return the column
   */
  public FutureOr<RegionColumn> column(int chunkX, int chunkZ, RegionGraph.ChunkColumns chunks) {
    long key = key(chunkX, chunkZ);
    CompletableFuture<RegionColumn> promise;
    synchronized (this) {
      RegionColumn known = memory.get(key);
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
      // Registered before any work starts: a disk read or a chunk that answers at once completes
      // the promise inside this call, and its completion has to find the entry to clear.
      promise = new CompletableFuture<>();
      loading.put(key, promise);
    }
    load(chunkX, chunkZ, chunks, promise);
    return FutureOr.from(promise);
  }

  private void load(
      int chunkX, int chunkZ, RegionGraph.ChunkColumns chunks, CompletableFuture<RegionColumn> to) {
    boolean stale;
    synchronized (this) {
      // Invalidated and not yet removed from disk: what is there is the terrain before the change.
      stale = removed.contains(key(chunkX, chunkZ));
    }
    if (files == null || stale) {
      build(chunkX, chunkZ, chunks, to);
      return;
    }
    disk.execute(
        () -> {
          RegionColumn stored = null;
          long started = System.nanoTime();
          try {
            stored = files.read(chunkX, chunkZ);
          } catch (IOException | RuntimeException unreadable) {
            // A file that cannot be read is a column that has to be built again; the next write
            // of that file replaces it.
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
            settle(chunkX, chunkZ, stored, false, to);
          } else {
            // Built on the build pool: the disk thread is one thread, shared by every world's reads
            // and writes, and a build is far slower than a read.
            builds.execute(() -> build(chunkX, chunkZ, chunks, to));
          }
        });
  }

  private void build(
      int chunkX, int chunkZ, RegionGraph.ChunkColumns chunks, CompletableFuture<RegionColumn> to) {
    FutureOr<MinecraftChunk> chunk;
    try {
      chunk = chunks.column(chunkX, chunkZ);
    } catch (RuntimeException failed) {
      fail(chunkX, chunkZ, to, failed);
      return;
    }
    if (chunk.isImmediate()) {
      settle(chunkX, chunkZ, built(chunkX, chunkZ, chunk.value()), chunk.value() != null, to);
      return;
    }
    // Built off the thread the chunk arrived on, which on a live server may be the main thread.
    chunk
        .future()
        .whenCompleteAsync(
            (arrived, failure) -> {
              try {
                MinecraftChunk readable = failure == null ? arrived : null;
                settle(chunkX, chunkZ, built(chunkX, chunkZ, readable), readable != null, to);
              } catch (RuntimeException failed) {
                fail(chunkX, chunkZ, to, failed);
              }
            },
            builds);
  }

  private RegionColumn built(int chunkX, int chunkZ, @Nullable MinecraftChunk chunk) {
    long started = System.nanoTime();
    RegionColumn column = builders.get().build(chunkX, chunkZ, chunk);
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
   * @param fresh whether it was built from a real chunk just now, and so belongs on disk; {@code
   *     false} for one read back from disk, or built empty because its chunk could not be read
   */
  private void settle(
      int chunkX,
      int chunkZ,
      RegionColumn column,
      boolean fresh,
      CompletableFuture<RegionColumn> to) {
    long key = key(chunkX, chunkZ);
    synchronized (this) {
      loading.remove(key);
      boolean changed = changedWhileLoading.remove(key);
      // An unreadable chunk is not kept on a server: it may be generated, or a load policy relax,
      // and a remembered empty column would wall it off for every later search. A store with no
      // disk serves one search, and keeps it so that search asks once.
      if (!changed && (fresh || column.regions() > 0 || files == null)) {
        remember(key, column);
        if (fresh && files != null) {
          unwritten.put(key, column);
          removed.remove(key);
        }
      }
    }
    to.complete(column);
  }

  private void fail(int chunkX, int chunkZ, CompletableFuture<RegionColumn> to, Throwable failure) {
    synchronized (this) {
      loading.remove(key(chunkX, chunkZ));
      changedWhileLoading.remove(key(chunkX, chunkZ));
    }
    to.completeExceptionally(failure);
  }

  /**
   * Builds a column from a chunk the server already has, if it is not known already.
   *
   * <p>For a chunk the server loads anyway — a player walking past — so that the terrain is built
   * before anyone navigates through it, at the cost of CPU and no reads. Call it off the server
   * thread: it may read the disk to learn whether the column is already stored.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @param chunk the chunk
   */
  public void prebuild(int chunkX, int chunkZ, MinecraftChunk chunk) {
    long key = key(chunkX, chunkZ);
    CompletableFuture<RegionColumn> promise = new CompletableFuture<>();
    synchronized (this) {
      if (memory.containsKey(key) || unwritten.containsKey(key) || loading.containsKey(key)) {
        return;
      }
      // In flight like any load: a search asking meanwhile waits on this build rather than
      // starting its own, and a block changing meanwhile keeps the result from being kept.
      loading.put(key, promise);
    }
    try {
      boolean stale;
      synchronized (this) {
        stale = removed.contains(key);
      }
      RegionColumn stored = files == null || stale ? null : files.read(chunkX, chunkZ);
      if (stored != null) {
        // Stored already. Kept in memory too, since a chunk loading is a sign it is about to be
        // needed; but not written again.
        settle(chunkX, chunkZ, stored, false, promise);
        return;
      }
    } catch (IOException | RuntimeException unreadable) {
      // Rebuild it, and the write replaces whatever is unreadable.
    }
    try {
      settle(chunkX, chunkZ, built(chunkX, chunkZ, chunk), true, promise);
    } catch (RuntimeException failed) {
      fail(chunkX, chunkZ, promise, failed);
    }
  }

  /**
   * Marks a chunk as loaded by the server: its column, once built, stays in memory until {@link
   * #chunkUnloaded}.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public synchronized void chunkLoaded(int chunkX, int chunkZ) {
    loadedChunks.add(key(chunkX, chunkZ));
  }

  /**
   * Marks a chunk as unloaded: its column is now kept only while the memory budget allows.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public synchronized void chunkUnloaded(int chunkX, int chunkZ) {
    loadedChunks.remove(key(chunkX, chunkZ));
    trim(Long.MIN_VALUE);
  }

  /**
   * Returns whether a column is in memory, unwritten, or on its way. Says nothing about disk.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   * @return {@code true} if it is known without reading anything
   */
  public synchronized boolean known(int chunkX, int chunkZ) {
    long key = key(chunkX, chunkZ);
    return memory.containsKey(key) || unwritten.containsKey(key) || loading.containsKey(key);
  }

  /**
   * Forgets a column because a block in it changed.
   *
   * <p>Cheap, and safe to call on the server thread for every block event.
   *
   * @param chunkX the chunk X
   * @param chunkZ the chunk Z
   */
  public synchronized void invalidate(int chunkX, int chunkZ) {
    long key = key(chunkX, chunkZ);
    RegionColumn dropped = memory.remove(key);
    if (dropped != null) {
      memoryBytes -= dropped.footprintBytes();
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
   * Releases the files the store holds open for reading, on the disk thread. Unwritten columns are
   * not written: {@link #flush} first.
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
   * @param columnsInMemory columns held in memory now
   * @param bytesInMemory their approximate footprint
   * @param columnsUnwritten columns built and not yet on disk
   * @param memoryHits columns answered from memory
   * @param diskLoads columns read back from disk
   * @param diskNanos time spent reading the disk, including reads that found nothing
   * @param builds columns built from chunks
   * @param buildNanos time spent building them
   * @param evictions columns dropped from memory for the budget
   * @param diskBytesRead compressed bytes read
   * @param diskBytesWritten bytes written
   */
  public record Stats(
      int columnsInMemory,
      long bytesInMemory,
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
    RegionColumn previous = memory.put(key, column);
    if (previous != null) {
      memoryBytes -= previous.footprintBytes();
    }
    memoryBytes += column.footprintBytes();
    trim(key);
  }

  /**
   * Evicts the least recently used columns past the budget, but never {@code keep} or a loaded one.
   */
  private void trim(long keep) {
    if (memoryBytes <= memoryBudget) {
      return;
    }
    List<Long> evicted = new ArrayList<>();
    Iterator<Map.Entry<Long, RegionColumn>> eldest = memory.entrySet().iterator();
    while (memoryBytes > memoryBudget && eldest.hasNext()) {
      Map.Entry<Long, RegionColumn> entry = eldest.next();
      if (entry.getKey() == keep || loadedChunks.contains(entry.getKey())) {
        continue; // never the one just asked for, nor where players are
      }
      memoryBytes -= entry.getValue().footprintBytes();
      evicted.add(entry.getKey());
      eldest.remove();
    }
    evictions += evicted.size();
  }

  private static long key(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
  }
}
