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
 * Every built {@link RegionStack} of one domain, shared by every search over it.
 *
 * <p><b>Three places a stack can come from,</b> tried in order: memory, disk, and building it from
 * the terrain. Building is the expensive one — it is most of what a search over new terrain spends
 * — and a stack only needs building again when a block in it changes, so a stack built once serves
 * every later search, by every agent, until then; and through {@link RegionFiles}, across restarts.
 * A stack built but not yet written is held until the next {@link #flush}, whatever the budget, so
 * evicting it never loses it.
 *
 * <p><b>Two kinds of stack in memory.</b> <em>Pinned</em> stacks are kept whatever the budget,
 * until their last pin is released: a navigating player's surroundings, so the short searches that
 * re-plan around them never build. The rest are kept up to a byte budget, least recently used first
 * out. The two are held apart, so evicting never has to look past a pinned stack.
 *
 * <p><b>Changes invalidate.</b> {@link #invalidate} drops a stack from memory, and from disk at the
 * next flush, and the next search to need it builds it again. A stack being built when its terrain
 * changes is handed to the search that asked for it — it is at most a moment stale — but not kept.
 * Only seams cross stacks, and seams are built per search, so a change never reaches a neighbour.
 *
 * <p>Thread-safe. Searches on several workers, block-change listeners on the server thread and
 * builds all use it at once; its maps are guarded by the store's own lock, and nothing slow —
 * building, reading, writing — happens while holding it.
 *
 * @param <C> the platform's stack of terrain
 */
public final class RegionStore<C> {

  private final RegionSpec<C> spec;
  private final long memoryBudget;
  private final @Nullable RegionFiles files;
  private final Executor disk;
  private final Executor builds;
  private final ThreadLocal<RegionStackBuilder<C>> builders;

  // --- guarded by this ---

  /** Stacks not pinned, least recently used first. */
  private final LinkedHashMap<Long, RegionStack> memory = new LinkedHashMap<>(256, 0.75f, true);

  private long memoryBytes;

  /** Pinned stacks that have been built. */
  private final Map<Long, RegionStack> pinned = new HashMap<>();

  private long pinnedBytes;

  /** How many pins hold each stack, built or not. */
  private final Map<Long, Integer> pinCounts = new HashMap<>();

  /** Each owner's pinned stacks. */
  private final Map<Object, Set<Long>> pins = new HashMap<>();

  /** Built since the last flush, and not yet on disk. Kept whatever the budget says. */
  private final Map<Long, RegionStack> unwritten = new HashMap<>();

  /** Invalidated since the last flush: to come off disk. */
  private final Set<Long> removed = new HashSet<>();

  private final Map<Long, CompletableFuture<RegionStack>> loading = new HashMap<>();
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
   * @param spec how the domain's terrain is cut into regions
   * @param memoryBudget how many bytes of stacks not pinned to keep in memory
   * @param directory where to keep stacks on disk, or {@code null} to keep them only in memory
   * @param disk where disk reads and writes run; one thread, so they never race
   * @param builds where a stack is built when its terrain arrives later rather than at once
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
    this.builders = ThreadLocal.withInitial(() -> new RegionStackBuilder<>(spec));
  }

  /**
   * Creates a store that keeps everything in memory, on the caller's thread: one search's worth, as
   * for a benchmark or a test.
   *
   * @param spec how the domain's terrain is cut into regions
   * @param <C> the platform's stack of terrain
   * @return the store
   */
  public static <C> RegionStore<C> inMemory(RegionSpec<C> spec) {
    return new RegionStore<>(spec, Long.MAX_VALUE, null, Runnable::run, Runnable::run);
  }

  /**
   * Returns how this store's stacks are built.
   *
   * @return the spec
   */
  public RegionSpec<C> spec() {
    return spec;
  }

  /**
   * Returns a stack, from memory, from disk, or by building it from terrain {@code source}
   * supplies.
   *
   * <p>Immediate when the stack is in memory, or when every step on the way to it — the disk read,
   * the terrain — answered at once. Otherwise a future that completes once the stack is ready; a
   * search should keep what it was handed rather than ask again, since a tight budget may already
   * have evicted it.
   *
   * @param stackX the stack X
   * @param stackZ the stack Z
   * @param source where to read the terrain if the stack has to be built
   * @return the stack
   */
  public FutureOr<RegionStack> stack(int stackX, int stackZ, TerrainSource<C> source) {
    long key = RegionStack.key(stackX, stackZ);
    CompletableFuture<RegionStack> promise;
    synchronized (this) {
      RegionStack known = pinned.get(key);
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
      CompletableFuture<RegionStack> inFlight = loading.get(key);
      if (inFlight != null) {
        return FutureOr.from(inFlight);
      }
      // Registered before any work starts: a disk read or terrain that answers at once completes
      // the promise inside this call, and its completion has to find the entry to clear.
      promise = new CompletableFuture<>();
      loading.put(key, promise);
    }
    load(stackX, stackZ, source, promise);
    return FutureOr.from(promise);
  }

  private void load(
      int stackX, int stackZ, TerrainSource<C> source, CompletableFuture<RegionStack> to) {
    boolean stale;
    synchronized (this) {
      // Invalidated and not yet removed from disk: what is there is the terrain before the change.
      stale = removed.contains(RegionStack.key(stackX, stackZ));
    }
    if (files == null || stale) {
      build(stackX, stackZ, source, to);
      return;
    }
    disk.execute(
        () -> {
          RegionStack stored;
          long started = System.nanoTime();
          try {
            stored = files.read(stackX, stackZ);
          } catch (IOException | RuntimeException unreadable) {
            // A file that cannot be read is a stack that has to be built again; the next write
            // of that stack replaces it.
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
            settle(stackX, stackZ, stored, true, false, to);
          } else {
            // Built on the build pool: the disk thread is one thread, shared by every domain's
            // reads
            // and writes, and a build is far slower than a read.
            builds.execute(() -> build(stackX, stackZ, source, to));
          }
        });
  }

  private void build(
      int stackX, int stackZ, TerrainSource<C> source, CompletableFuture<RegionStack> to) {
    FutureOr<C> terrain;
    try {
      terrain = source.stack(stackX, stackZ);
    } catch (RuntimeException failed) {
      fail(stackX, stackZ, to, failed);
      return;
    }
    if (terrain.isImmediate()) {
      boolean readable = terrain.value() != null;
      try {
        settle(stackX, stackZ, built(stackX, stackZ, terrain.value()), readable, readable, to);
      } catch (RuntimeException failed) {
        // Failed, not thrown: left in flight, the stack would park every later search on it.
        fail(stackX, stackZ, to, failed);
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
                boolean real = readable != null;
                settle(stackX, stackZ, built(stackX, stackZ, readable), real, real, to);
              } catch (RuntimeException failed) {
                fail(stackX, stackZ, to, failed);
              }
            },
            builds);
  }

  private RegionStack built(int stackX, int stackZ, @Nullable C terrain) {
    long started = System.nanoTime();
    RegionStack stack = builders.get().build(stackX, stackZ, terrain);
    long took = System.nanoTime() - started;
    synchronized (this) {
      built++;
      buildNanos += took;
    }
    return stack;
  }

  /**
   * Keeps a stack that has arrived, unless its terrain changed meanwhile, and hands it over.
   *
   * <p>A stack whose terrain could not be read is handed over but not kept: the terrain may be
   * generated, or a load policy relax, and a remembered empty stack would wall it off for every
   * later search.
   *
   * @param keep whether it describes real terrain, built or read back
   * @param fresh whether it was built just now, and so belongs on disk
   */
  private void settle(
      int stackX,
      int stackZ,
      RegionStack stack,
      boolean keep,
      boolean fresh,
      CompletableFuture<RegionStack> to) {
    long key = RegionStack.key(stackX, stackZ);
    synchronized (this) {
      loading.remove(key);
      boolean changed = changedWhileLoading.remove(key);
      if (!changed && keep) {
        remember(key, stack);
        if (fresh && files != null) {
          unwritten.put(key, stack);
          removed.remove(key);
        }
      }
    }
    to.complete(stack);
  }

  private void fail(int stackX, int stackZ, CompletableFuture<RegionStack> to, Throwable failure) {
    synchronized (this) {
      loading.remove(RegionStack.key(stackX, stackZ));
      changedWhileLoading.remove(RegionStack.key(stackX, stackZ));
    }
    to.completeExceptionally(failure);
  }

  /**
   * Pins the stacks within {@code radius} of a stack for an owner, releasing whatever the owner had
   * pinned before.
   *
   * <p>A pinned stack is kept in memory whatever the budget, once built; pinning does not build it.
   * A stack pinned by several owners stays until the last releases it.
   *
   * @param owner whoever the pin is for, such as a navigating player's id
   * @param stackX the centre stack X
   * @param stackZ the centre stack Z
   * @param radius how many stacks out from the centre, along X and Z
   */
  public synchronized void pin(Object owner, int stackX, int stackZ, int radius) {
    Set<Long> wanted = new HashSet<>();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        wanted.add(RegionStack.key(stackX + dx, stackZ + dz));
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
   * Releases everything an owner has pinned. Its stacks are then kept only as the budget allows.
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
      RegionStack stack = memory.remove(key);
      if (stack != null) {
        memoryBytes -= stack.footprintBytes();
        pinned.put(key, stack);
        pinnedBytes += stack.footprintBytes();
      }
    }
  }

  private void letGo(long key) {
    if (pinCounts.merge(key, -1, Integer::sum) == 0) {
      pinCounts.remove(key);
      RegionStack stack = pinned.remove(key);
      if (stack != null) {
        pinnedBytes -= stack.footprintBytes();
        // Most recently used: the owner was just near it.
        memory.put(key, stack);
        memoryBytes += stack.footprintBytes();
      }
    }
  }

  /**
   * Forgets a stack because a block in it changed.
   *
   * <p>Cheap, and safe to call on the server thread for every block event.
   *
   * @param stackX the stack X
   * @param stackZ the stack Z
   */
  public synchronized void invalidate(int stackX, int stackZ) {
    long key = RegionStack.key(stackX, stackZ);
    RegionStack dropped = memory.remove(key);
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
   * Returns whether a stack is in memory, pinned or not. Says nothing about disk.
   *
   * @param stackX the stack X
   * @param stackZ the stack Z
   * @return {@code true} if it is
   */
  public synchronized boolean inMemory(int stackX, int stackZ) {
    long key = RegionStack.key(stackX, stackZ);
    return memory.containsKey(key) || pinned.containsKey(key);
  }

  /**
   * Writes every stack built since the last flush, and removes every invalidated one, on the disk
   * thread.
   *
   * <p>A write that fails puts its stacks back, so the next flush tries again.
   *
   * @return a future completing when the write is done
   */
  public CompletableFuture<Void> flush() {
    if (files == null) {
      return CompletableFuture.completedFuture(null);
    }
    Map<Long, RegionStack> writing;
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
              // Not a stack invalidated while the write ran: putting it back would serve, and
              // then write, the terrain before the change.
              writing.forEach(
                  (key, stack) -> {
                    if (!removed.contains(key)) {
                      unwritten.putIfAbsent(key, stack);
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
   * Releases the files the store holds open, on the disk thread. Unwritten stacks are not written:
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
   * @param unpinnedColumns stacks held in memory now that are not pinned, within the budget
   * @param unpinnedBytes their approximate footprint
   * @param pinnedColumns pinned stacks held in memory now, beyond the budget
   * @param pinnedBytes their approximate footprint
   * @param unwrittenStacks stacks built and not yet on disk
   * @param memoryHits stacks answered from memory
   * @param diskLoads stacks read back from disk
   * @param diskNanos time spent reading the disk, including reads that found nothing
   * @param builds stacks built from terrain
   * @param buildNanos time spent building them
   * @param evictions stacks dropped from memory for the budget
   * @param diskBytesRead record bytes read
   * @param diskBytesWritten bytes written
   */
  public record Stats(
      int unpinnedColumns,
      long unpinnedBytes,
      int pinnedColumns,
      long pinnedBytes,
      int unwrittenStacks,
      long memoryHits,
      long diskLoads,
      long diskNanos,
      long builds,
      long buildNanos,
      long evictions,
      long diskBytesRead,
      long diskBytesWritten) {}

  /** Puts a stack in memory as most recently used, evicting the least recent past the budget. */
  private void remember(long key, RegionStack stack) {
    if (pinCounts.containsKey(key)) {
      RegionStack previous = pinned.put(key, stack);
      if (previous != null) {
        pinnedBytes -= previous.footprintBytes();
      }
      pinnedBytes += stack.footprintBytes();
      return;
    }
    RegionStack previous = memory.put(key, stack);
    if (previous != null) {
      memoryBytes -= previous.footprintBytes();
    }
    memoryBytes += stack.footprintBytes();
    trim(key);
  }

  /** Evicts the least recently used stacks past the budget, but never {@code keep}. */
  private void trim(long keep) {
    Iterator<Map.Entry<Long, RegionStack>> eldest = memory.entrySet().iterator();
    while (memoryBytes > memoryBudget && eldest.hasNext()) {
      Map.Entry<Long, RegionStack> entry = eldest.next();
      if (entry.getKey() == keep) {
        continue; // never the one just asked for
      }
      memoryBytes -= entry.getValue().footprintBytes();
      eldest.remove();
      evictions++;
    }
  }
}
