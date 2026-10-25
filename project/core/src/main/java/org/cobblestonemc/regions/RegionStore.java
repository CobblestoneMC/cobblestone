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
 * Every built {@link RegionStack} of one domain, shared by every search over it.
 *
 * <p><b>Built once, kept in memory.</b> Building a stack is most of what a search over new terrain
 * spends, and a stack only needs building again when a block in it changes, so a stack built once
 * serves every later search, by every agent, until then.
 *
 * <p><b>Two kinds of stack in memory.</b> <em>Pinned</em> stacks are kept whatever the budget,
 * until their last pin is released: a navigating player's surroundings, so the short searches that
 * re-plan around them never build. The rest are kept up to a byte budget, least recently used first
 * out. The two are held apart, so evicting never has to look past a pinned stack.
 *
 * <p><b>Changes invalidate.</b> {@link #invalidate} drops a stack, and the next search to need it
 * builds it again. A stack being built when its terrain changes is handed to the search that asked
 * for it — it is at most a moment stale — but not kept. Only seams cross stacks, and seams are
 * built per search, so a change never reaches a neighbour.
 *
 * <p>Thread-safe. Searches on several workers, block-change listeners on the server thread and
 * builds all use it at once; its maps are guarded by the store's own lock, and nothing slow —
 * building — happens while holding it.
 *
 * @param <C> the platform's stack of terrain
 */
public final class RegionStore<C> {

  private final RegionSpec<C> spec;
  private final long memoryBudget;
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

  private final Map<Long, CompletableFuture<RegionStack>> loading = new HashMap<>();
  private final Set<Long> changedWhileLoading = new HashSet<>();

  private long memoryHits;
  private long built;
  private long buildNanos;
  private long evictions;

  /**
   * Creates a store.
   *
   * @param spec how the domain's terrain is cut into regions
   * @param memoryBudget how many bytes of stacks not pinned to keep in memory
   * @param builds where a stack is built when its terrain arrives later rather than at once
   */
  public RegionStore(RegionSpec<C> spec, long memoryBudget, Executor builds) {
    this.spec = spec;
    this.memoryBudget = memoryBudget;
    this.builds = builds;
    this.builders = ThreadLocal.withInitial(() -> new RegionStackBuilder<>(spec));
  }

  /**
   * Creates a store that keeps everything, on the caller's thread: one search's worth, as for a
   * benchmark or a test.
   *
   * @param spec how the domain's terrain is cut into regions
   * @param <C> the platform's stack of terrain
   * @return the store
   */
  public static <C> RegionStore<C> inMemory(RegionSpec<C> spec) {
    return new RegionStore<>(spec, Long.MAX_VALUE, Runnable::run);
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
   * Returns a stack, from memory or by building it from terrain {@code source} supplies.
   *
   * <p>Immediate when the stack is in memory, or when its terrain answered at once. Otherwise a
   * future that completes once the stack is built; a search should keep what it was handed rather
   * than ask again, since a tight budget may already have evicted it.
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
      if (known != null) {
        memoryHits++;
        return FutureOr.of(known);
      }
      CompletableFuture<RegionStack> inFlight = loading.get(key);
      if (inFlight != null) {
        return FutureOr.from(inFlight);
      }
      // Registered before any work starts: terrain that answers at once completes the promise
      // inside this call, and its completion has to find the entry to clear.
      promise = new CompletableFuture<>();
      loading.put(key, promise);
    }
    build(stackX, stackZ, source, promise);
    return FutureOr.from(promise);
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
      try {
        settle(stackX, stackZ, built(stackX, stackZ, terrain.value()), terrain.value(), to);
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
                settle(stackX, stackZ, built(stackX, stackZ, readable), readable, to);
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
   * Keeps a stack that has been built, unless its terrain changed meanwhile, and hands it over.
   *
   * <p>A stack whose terrain could not be read is handed over but not kept: the terrain may be
   * generated, or a load policy relax, and a remembered empty stack would wall it off for every
   * later search.
   */
  private void settle(
      int stackX,
      int stackZ,
      RegionStack stack,
      @Nullable C terrain,
      CompletableFuture<RegionStack> to) {
    long key = RegionStack.key(stackX, stackZ);
    synchronized (this) {
      loading.remove(key);
      boolean changed = changedWhileLoading.remove(key);
      if (!changed && terrain != null) {
        remember(key, stack);
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
    if (loading.containsKey(key)) {
      changedWhileLoading.add(key);
    }
  }

  /**
   * Returns whether a stack is in memory, pinned or not.
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
   * @param unpinnedColumns stacks held in memory now that are not pinned, within the budget
   * @param unpinnedBytes their approximate footprint
   * @param pinnedColumns pinned stacks held in memory now, beyond the budget
   * @param pinnedBytes their approximate footprint
   * @param memoryHits stacks answered from memory
   * @param builds stacks built from terrain
   * @param buildNanos time spent building them
   * @param evictions stacks dropped from memory for the budget
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
