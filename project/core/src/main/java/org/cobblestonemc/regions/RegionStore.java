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
 * Every built {@link RegionSection} of one domain, shared by every search over it.
 *
 * <p><b>Three places a section can come from,</b> tried in order: memory, disk, and building it
 * from the terrain. Building is the expensive one — it is most of what a search over new terrain
 * spends — and a section only needs building again when a block it was read from changes, so a
 * section built once serves every later search, by every agent, until then; and through {@link
 * RegionFiles}, across restarts. A section built but not yet written is held until the next {@link
 * #flush}, whatever the budget, so evicting it never loses it.
 *
 * <p><b>Two kinds of section in memory.</b> <em>Pinned</em> sections are kept whatever the budget,
 * until their last pin is released: a navigating player's surroundings, so the short searches that
 * re-plan around them never build. The rest are kept up to a byte budget, least recently used first
 * out. The two are held apart, so evicting never has to look past a pinned section.
 *
 * <p><b>Changes invalidate.</b> {@link #invalidate} drops a section from memory, and from disk at
 * the next flush, and the next search to need it builds it again. A section being built when its
 * terrain changes is handed to the search that asked for it — it is at most a moment stale — but
 * not kept. Only seams cross sections, and seams are built per search, so a change never reaches a
 * neighbour.
 *
 * <p>Thread-safe. Searches on several workers, block-change listeners on the server thread and
 * builds all use it at once; its maps are guarded by the store's own lock, and nothing slow —
 * building, reading, writing — happens while holding it.
 *
 * @param <C> the platform's terrain
 * @param <K> the terrain's classes
 */
public final class RegionStore<C, K extends Enum<K> & RegionKind> {

  private final RegionSpec<C, K> spec;
  private final long memoryBudget;
  private final @Nullable RegionFiles files;
  private final Executor disk;
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

  /** Built since the last flush, and not yet on disk. Kept whatever the budget says. */
  private final Map<Long, RegionSection> unwritten = new HashMap<>();

  /** Invalidated since the last flush: to come off disk. */
  private final Set<Long> removed = new HashSet<>();

  private final Map<Long, CompletableFuture<RegionSection>> loading = new HashMap<>();
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
   * @param memoryBudget how many bytes of sections not pinned to keep in memory
   * @param directory where to keep sections on disk, or {@code null} to keep them only in memory
   * @param disk where disk reads and writes run; one thread, so they never race
   * @param builds where a section is built when its terrain arrives later rather than at once
   */
  public RegionStore(
      RegionSpec<C, K> spec,
      long memoryBudget,
      @Nullable Path directory,
      Executor disk,
      Executor builds) {
    this.spec = spec;
    this.memoryBudget = memoryBudget;
    this.files = directory == null ? null : new RegionFiles(directory, spec);
    this.disk = disk;
    this.builds = builds;
    this.builders = ThreadLocal.withInitial(() -> new RegionSectionBuilder<>(spec));
  }

  /**
   * Creates a store that keeps everything in memory, on the caller's thread: one search's worth, as
   * for a benchmark or a test.
   *
   * @param spec how the domain's terrain is cut into regions
   * @param <C> the platform's terrain
   * @param <K> the terrain's classes
   * @return the store
   */
  public static <C, K extends Enum<K> & RegionKind> RegionStore<C, K> inMemory(
      RegionSpec<C, K> spec) {
    return new RegionStore<>(spec, Long.MAX_VALUE, null, Runnable::run, Runnable::run);
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
   * Returns a section, from memory, from disk, or by building it from terrain {@code source}
   * supplies.
   *
   * <p>Immediate when the section is in memory, when every step on the way to it — the disk read,
   * the terrain — answered at once, or when it is wholly outside the domain, which is never read.
   * Otherwise a future that completes once the section is ready; a search should keep what it was
   * handed rather than ask again, since a tight budget may already have evicted it.
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
      CompletableFuture<RegionSection> inFlight = loading.get(key);
      if (inFlight != null) {
        return FutureOr.from(inFlight);
      }
      // Registered before any work starts: a disk read or terrain that answers at once completes
      // the promise inside this call, and its completion has to find the entry to clear.
      promise = new CompletableFuture<>();
      loading.put(key, promise);
    }
    load(sectionX, sectionY, sectionZ, key, source, promise);
    return FutureOr.from(promise);
  }

  private void load(
      int sx,
      int sy,
      int sz,
      long key,
      TerrainSource<C> source,
      CompletableFuture<RegionSection> to) {
    boolean stale;
    synchronized (this) {
      // Invalidated and not yet removed from disk: what is there is the terrain before the change.
      stale = removed.contains(key);
    }
    if (files == null || stale) {
      build(sx, sy, sz, key, source, to);
      return;
    }
    disk.execute(
        () -> {
          RegionSection stored;
          long started = System.nanoTime();
          try {
            stored = files.read(sx, sy, sz);
          } catch (IOException | RuntimeException unreadable) {
            // A file that cannot be read is a section that has to be built again; the next write
            // of that section replaces it.
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
            settle(key, stored, true, false, to);
          } else {
            // Built on the build pool: the disk thread is one thread, shared by every domain's
            // reads and writes, and a build is far slower than a read.
            builds.execute(() -> build(sx, sy, sz, key, source, to));
          }
        });
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
      boolean readable = terrain.value() != null;
      try {
        settle(key, built(sx, sy, sz, terrain.value()), readable, readable, to);
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
                boolean real = readable != null;
                settle(key, built(sx, sy, sz, readable), real, real, to);
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
   * Keeps a section that has arrived, unless its terrain changed meanwhile, and hands it over.
   *
   * <p>A section whose terrain could not be read is handed over but not kept: the terrain may be
   * generated, or a load policy relax, and a remembered empty section would wall it off for every
   * later search.
   *
   * @param keep whether it describes real terrain, built or read back
   * @param fresh whether it was built just now, and so belongs on disk
   */
  private void settle(
      long key,
      RegionSection section,
      boolean keep,
      boolean fresh,
      CompletableFuture<RegionSection> to) {
    synchronized (this) {
      loading.remove(key);
      boolean changed = changedWhileLoading.remove(key);
      if (!changed && keep) {
        remember(key, section);
        if (fresh && files != null) {
          unwritten.put(key, section);
          removed.remove(key);
        }
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
    unwritten.remove(key);
    if (loading.containsKey(key)) {
      changedWhileLoading.add(key);
    }
    if (files != null) {
      removed.add(key);
    }
  }

  /**
   * Returns whether a section is in memory, pinned or not. Says nothing about disk.
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
   * Writes every section built since the last flush, and removes every invalidated one, on the disk
   * thread.
   *
   * <p>A write that fails puts its sections back, so the next flush tries again.
   *
   * @return a future completing when the write is done
   */
  public CompletableFuture<Void> flush() {
    if (files == null) {
      return CompletableFuture.completedFuture(null);
    }
    Map<Long, RegionSection> writing;
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
              // Not a section invalidated while the write ran: putting it back would serve, and
              // then write, the terrain before the change.
              writing.forEach(
                  (key, section) -> {
                    if (!removed.contains(key)) {
                      unwritten.putIfAbsent(key, section);
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
   * Releases the files the store holds open, on the disk thread. Unwritten sections are not
   * written: {@link #flush} first.
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
   * @param unpinnedSections sections held in memory now that are not pinned, within the budget
   * @param unpinnedBytes their approximate footprint
   * @param pinnedSections pinned sections held in memory now, beyond the budget
   * @param pinnedBytes their approximate footprint
   * @param unwrittenSections sections built and not yet on disk
   * @param memoryHits sections answered from memory
   * @param diskLoads sections read back from disk
   * @param diskNanos time spent reading the disk, including reads that found nothing
   * @param builds sections built from terrain
   * @param buildNanos time spent building them
   * @param evictions sections dropped from memory for the budget
   * @param diskBytesRead record bytes read
   * @param diskBytesWritten bytes written
   */
  public record Stats(
      int unpinnedSections,
      long unpinnedBytes,
      int pinnedSections,
      long pinnedBytes,
      int unwrittenSections,
      long memoryHits,
      long diskLoads,
      long diskNanos,
      long builds,
      long buildNanos,
      long evictions,
      long diskBytesRead,
      long diskBytesWritten) {}

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
